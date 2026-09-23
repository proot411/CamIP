package com.camip.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps Android's hardware-accelerated MediaCodec H.264/AVC encoder in
 * "surface input" mode so Camera2 can render directly into the encoder's
 * input Surface with no extra CPU copy or software conversion step.
 *
 * The encoder is never used to decode/re-encode anything: it only ever sees
 * frames produced by the camera and only ever emits H.264 Access Units,
 * which are handed to [Listener] completely unmodified aside from splitting
 * codec-config (SPS/PPS) from regular frame data.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrateBps: Int,
    private val frameRate: Int,
    private val keyFrameIntervalSeconds: Int,
    private val listener: Listener
) {
    interface Listener {
        /** SPS/PPS (Annex-B, start codes included) became available. */
        fun onCodecConfig(sps: ByteArray, pps: ByteArray)

        /** One encoded access unit (Annex-B, start codes included) is ready. */
        fun onAccessUnit(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean)

        fun onEncoderError(message: String, error: Throwable?)
    }

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    /**
     * Builds the encoder format.
     *
     * @param lowLatencyHints include every latency-reducing hint this API
     *   level can express (operating rate, max B-frames = 0, KEY_LATENCY).
     * @param baselineProfile request AVC Baseline, which structurally
     *   cannot produce B-frames. Only used below Android 10, where
     *   [MediaFormat.KEY_MAX_B_FRAMES] does not exist yet.
     *
     * B-frames matter here: MpegTsMuxer stamps every PES with PTS only
     * (DTS == PTS), which is only correct for streams without frame
     * reordering. If an encoder emitted B-frames anyway, FFmpeg would see
     * contradictory timestamps and buffer/delay the stream.
     */
    private fun buildFormat(lowLatencyHints: Boolean, baselineProfile: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (baselineProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                // Level has to fit the frame size: 1080p (and the taller
                // screen-share sizes) exceed level 3.1's frame budget, which
                // makes configure() fail on strict encoders.
                val pixels = width * height
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    if (pixels > 1280 * 720) MediaCodecInfo.CodecProfileLevel.AVCLevel4
                    else MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )
            }
            if (!lowLatencyHints) return@apply
            // Let the codec pre-allocate and schedule for the rate it will
            // actually see; a too-low operating rate makes several hardware
            // encoders queue frames internally (i.e. add latency).
            setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate * 2)
            // Best-effort low latency hints. Not every device/encoder honors
            // these, which is why we don't assume they are guaranteed.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime priority
        }
    }

    /**
     * Configures and starts the encoder, returning the input [Surface] that
     * Camera2's capture session should target.
     */
    @Throws(Exception::class)
    fun start(): Surface {
        // Below Android 10 there is no KEY_MAX_B_FRAMES, so ask for Baseline
        // instead to guarantee the "PTS == DTS" assumption of the muxer.
        val wantsBaseline = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        try {
            encoder.configure(buildFormat(lowLatencyHints = true, baselineProfile = wantsBaseline), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some devices reject individual hints; fall back to a plain
            // configuration rather than failing to stream at all.
            Log.w(TAG, "Low-latency encoder config rejected (${e.message}); retrying with defaults")
            try {
                encoder.reset()
            } catch (_: Exception) {
            }
            encoder.configure(buildFormat(lowLatencyHints = false, baselineProfile = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = encoder.createInputSurface()
        encoder.start()

        codec = encoder
        inputSurface = surface
        running.set(true)
        drainThread = Thread({ drainLoop() }, "CamIP-EncoderDrain").apply {
            isDaemon = true
            start()
        }
        return surface
    }

    /** Requests a synchronous keyframe on the next encoded frame, if supported by the device. */
    fun requestKeyFrame() {
        val c = codec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyFrame not supported on this device: ${e.message}")
        }
    }

    /** Adjusts bitrate live where the device/encoder supports dynamic bitrate changes. */
    fun setBitrate(newBitrateBps: Int) {
        val c = codec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            c.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic bitrate change not supported: ${e.message}")
        }
    }

    private fun drainLoop() {
        // Encoded output that sits in the codec's output queue is latency
        // the viewer can see. Run the drain loop above normal priority so
        // frames leave MediaCodec as soon as they are produced.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {
        }

        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val outIndex = try {
                c.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (e: Exception) {
                if (running.get()) listener.onEncoderError("dequeueOutputBuffer failed", e)
                break
            }

            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Surface-input AVC encoders typically don't require
                    // anything special here since SPS/PPS also arrive via a
                    // BUFFER_FLAG_CODEC_CONFIG buffer, handled below.
                    Log.d(TAG, "Output format changed: ${c.outputFormat}")
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // no output yet, loop again
                }
                outIndex >= 0 -> {
                    try {
                        val outBuffer: ByteBuffer? = c.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            handleOutputBuffer(outBuffer, bufferInfo)
                        }
                    } catch (e: Exception) {
                        // Only surface errors from a codec that is supposed to
                        // be running: stop() races with an in-flight buffer all
                        // the time, and reporting that as a fatal pipeline
                        // error would tear down the *next* stage mid-switch.
                        if (running.get()) listener.onEncoderError("Failed to process encoder output", e)
                    } finally {
                        try {
                            c.releaseOutputBuffer(outIndex, false)
                        } catch (e: Exception) {
                            // Codec was stopped/released underneath us; exit
                            // quietly instead of killing the drain thread with
                            // an uncaught exception.
                            if (running.get()) {
                                listener.onEncoderError("releaseOutputBuffer failed", e)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun handleOutputBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val raw = ByteArray(info.size)
        buffer.get(raw)

        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        // The Android AVC encoder emits Annex-B (start-code-delimited) NAL
        // units for its raw ByteBuffer output; we do not assume the very
        // first byte is a NAL header; instead we scan for 00 00 01 / 00 00
        // 00 01 start codes so both 3- and 4-byte forms are handled.
        val nalUnits = NalUnitParser.splitAnnexB(raw)
        if (nalUnits.isEmpty()) return

        if (isConfig) {
            // Codec-config buffers usually contain SPS followed by PPS (both
            // Annex-B, start codes intact). Cache them individually by NAL type.
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in nalUnits) {
                when (NalUnitParser.nalType(nal)) {
                    7 -> sps = nal // SPS
                    8 -> pps = nal // PPS
                }
            }
            if (sps != null && pps != null) {
                cachedSps = sps
                cachedPps = pps
                listener.onCodecConfig(sps, pps)
            }
            return
        }

        // Regular access unit: pass the full Annex-B buffer through as-is.
        // If this happens to be a keyframe access unit that doesn't already
        // include SPS/PPS inline (device-dependent), the muxer is
        // responsible for prefixing the cached parameter sets.
        val containsIdr = nalUnits.any { NalUnitParser.nalType(it) == 5 }
        listener.onAccessUnit(raw, info.presentationTimeUs, isKeyFrame || containsIdr)
    }

    fun stop() {
        running.set(false)
        val drain = drainThread
        if (drain != null && Thread.currentThread() !== drain) {
            // Never join/interrupt the drain thread from itself: a fatal
            // encoder error can surface on that very thread.
            drain.interrupt()
            try {
                drain.join(500)
            } catch (_: InterruptedException) {
            }
        }
        drainThread = null
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop() failed: ${e.message}")
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec.release() failed: ${e.message}")
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }
}
