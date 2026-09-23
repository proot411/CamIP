package com.camip.app.stream

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends MPEG-TS packets over UDP to a configurable destination.
 *
 * TS packets are grouped 7-per-datagram (7 x 188 = 1316 bytes), which is the
 * conventional grouping used by MPEG-TS-over-UDP senders because it stays
 * comfortably under a standard 1500-byte Ethernet/Wi-Fi MTU once UDP/IP
 * headers are added. Groups are flushed immediately (no batching delay
 * beyond "wait for 7 packets or end-of-access-unit") to keep latency low.
 *
 * Sending is fire-and-forget UDP: if nobody is listening on the destination
 * port, sends simply have no effect (aside from occasional ICMP port
 * unreachable noise on some networks) and streaming continues unaffected,
 * exactly as required for OBS to be able to connect/disconnect freely.
 */
class UdpStreamer {

    companion object {
        private const val TAG = "UdpStreamer"
        private const val PACKETS_PER_DATAGRAM = 7 // 7 * 188 = 1316 bytes
        private const val QUEUE_CAPACITY = 512
    }

    private var socket: DatagramSocket? = null
    private var destinationAddress: InetAddress? = null
    private var destinationPort: Int = 0

    private val running = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val pending = ByteArrayOutputStream(PACKETS_PER_DATAGRAM * MpegTsMuxer.TS_PACKET_SIZE)
    private var pendingCount = 0

    private val _packetsSent = AtomicLong(0)
    private val _sendErrors = AtomicLong(0)
    val packetsSent: Long get() = _packetsSent.get()
    val sendErrors: Long get() = _sendErrors.get()

    @Volatile var onError: ((String) -> Unit)? = null

    @Throws(Exception::class)
    fun start(destIp: String, destPort: Int) {
        stop()
        destinationAddress = InetAddress.getByName(destIp)
        destinationPort = destPort
        socket = DatagramSocket().apply {
            // Small send buffer is fine; we never want the OS to accumulate
            // a large backlog of stale video data.
            sendBufferSize = 64 * 1024
        }
        running.set(true)
        pending.reset()
        pendingCount = 0
        queue.clear()
        senderThread = Thread({ senderLoop() }, "CamIP-UdpSender").apply {
            isDaemon = true
            start()
        }
    }

    /** Update destination without tearing down the whole streaming session. */
    fun updateDestination(destIp: String, destPort: Int) {
        try {
            destinationAddress = InetAddress.getByName(destIp)
            destinationPort = destPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update destination", e)
            onError?.invoke("Invalid destination: ${e.message}")
        }
    }

    /** Enqueue one 188-byte TS packet for transmission. Called from the muxer's callback. */
    fun offer(tsPacket: ByteArray) {
        if (!running.get()) return
        if (!queue.offer(tsPacket)) {
            // Queue is full: drop the oldest packet rather than let latency
            // grow unbounded. This favors "recent and slightly broken" video
            // over "smooth but stale" video, consistent with the low-latency
            // priorities in the spec.
            queue.poll()
            queue.offer(tsPacket)
        }
    }

    private fun senderLoop() {
        while (running.get()) {
            val packet = try {
                queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            pending.write(packet)
            pendingCount++
            if (pendingCount >= PACKETS_PER_DATAGRAM || queue.isEmpty()) {
                flushDatagram()
            }
        }
        flushDatagram()
    }

    private fun flushDatagram() {
        if (pendingCount == 0) return
        val bytes = pending.toByteArray()
        pending.reset()
        pendingCount = 0
        val addr = destinationAddress ?: return
        val sock = socket ?: return
        try {
            val datagram = DatagramPacket(bytes, bytes.size, addr, destinationPort)
            sock.send(datagram)
            _packetsSent.incrementAndGet()
        } catch (e: Exception) {
            _sendErrors.incrementAndGet()
            Log.w(TAG, "UDP send failed: ${e.message}")
            onError?.invoke("UDP send failed: ${e.message}")
        }
    }

    fun stop() {
        running.set(false)
        senderThread?.interrupt()
        try {
            senderThread?.join(500)
        } catch (_: InterruptedException) {
        }
        senderThread = null
        socket?.close()
        socket = null
        pending.reset()
        pendingCount = 0
        queue.clear()
    }
}
