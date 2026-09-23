package com.camip.app.stream

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal but spec-correct MPEG Transport Stream muxer for a single H.264
 * (AVC) elementary stream, built specifically for this project's low-latency
 * "encode once, packetize, send" pipeline.
 *
 * This is NOT a general-purpose muxer. It intentionally supports exactly one
 * video stream and nothing else, which keeps it small enough to audit while
 * remaining correct enough for FFmpeg/ffplay/VLC/OBS to lock onto:
 *
 *  - 188-byte TS packets
 *  - PAT (PID 0x0000) describing one program
 *  - PMT (PID 0x1000) describing one H.264 stream (stream_type 0x1B) with
 *    PCR carried on the video PID itself
 *  - PES packets (stream_id 0xE0) carrying full Annex-B access units
 *    (SPS/PPS prefixed onto every keyframe access unit)
 *  - Per-PID continuity counters
 *  - PCR stamped on the first TS packet of every access unit
 *  - PAT/PMT re-sent before every keyframe and at a periodic interval, so a
 *    receiver (OBS, ffplay) that joins mid-stream can acquire the program
 *    and start decoding at the next IDR frame.
 *
 * All output is delivered as complete 188-byte TS packets via [onPacket].
 * The caller (UdpStreamer) is responsible for grouping packets into UDP
 * datagrams and transmitting them.
 */
class MpegTsMuxer(private val onPacket: (ByteArray) -> Unit) {

    companion object {
        const val TS_PACKET_SIZE = 188
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private const val PROGRAM_NUMBER = 1
        private const val STREAM_TYPE_H264 = 0x1B

        // Re-announce PAT/PMT at least this often (in access units) even
        // when no keyframe occurs, so a late joiner never waits too long.
        private const val PSI_REPEAT_INTERVAL_FRAMES = 30

        private const val SYNC_BYTE = 0x47

        // 90kHz clock used by both PTS/DTS and PCR.
        const val PTS_CLOCK_HZ = 90_000L
    }

    private val continuityCounters = HashMap<Int, AtomicInteger>()
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null
    private var framesSincePsi = PSI_REPEAT_INTERVAL_FRAMES // force PSI before first frame
    private var patPmtVersion = 0

    @Synchronized
    fun reset() {
        continuityCounters.clear()
        spsBytes = null
        ppsBytes = null
        framesSincePsi = PSI_REPEAT_INTERVAL_FRAMES
    }

    /** Called once codec config (SPS/PPS) is available from MediaCodec, and again if it changes. */
    @Synchronized
    fun setParameterSets(sps: ByteArray, pps: ByteArray) {
        spsBytes = sps
        ppsBytes = pps
        // Force PAT/PMT + parameter sets back out immediately on (re)configuration.
        framesSincePsi = PSI_REPEAT_INTERVAL_FRAMES
    }

    /**
     * Packetizes one encoded access unit (all NAL units belonging to a
     * single presentation timestamp, in Annex-B form with start codes) into
     * TS packets and emits them via [onPacket].
     *
     * @param accessUnit Annex-B NAL units for this frame (start codes intact).
     * @param presentationTimeUs presentation timestamp from MediaCodec, in microseconds.
     * @param isKeyFrame true for IDR access units.
     */
    @Synchronized
    fun writeAccessUnit(accessUnit: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        val pts90k = ptsFromUs(presentationTimeUs)

        val needsPsi = isKeyFrame || framesSincePsi >= PSI_REPEAT_INTERVAL_FRAMES
        if (needsPsi) {
            writePat()
            writePmt()
            framesSincePsi = 0
        } else {
            framesSincePsi++
        }

        val payload = if (isKeyFrame) {
            val sps = spsBytes
            val pps = ppsBytes
            if (sps != null && pps != null) {
                val out = ByteArrayOutputStream(sps.size + pps.size + accessUnit.size)
                out.write(sps)
                out.write(pps)
                out.write(accessUnit)
                out.toByteArray()
            } else accessUnit
        } else accessUnit

        val pes = buildPesPacket(payload, pts90k)
        writePesAsTsPackets(pes, pts90k, carryPcr = true)
    }

    // ---------------------------------------------------------------------
    // PAT
    // ---------------------------------------------------------------------

    private fun writePat() {
        val section = ByteArrayOutputStream()
        section.write(0x00) // table_id: program_association_section
        // section_length filled after we know body length
        val body = ByteArrayOutputStream()
        body.write16(PROGRAM_NUMBER)
        body.write16(0xE000 or PMT_PID) // reserved bits '111' + PMT PID
        val bodyBytes = body.toByteArray()

        val sectionLength = 5 /* after section_length field, before CRC */ + bodyBytes.size + 4 /* CRC32 */
        section.write16(0xB000 or sectionLength) // section_syntax_indicator=1, reserved='11', length
        section.write16(0x0001) // transport_stream_id
        section.write(0xC1) // reserved '11' + version_number '00000' + current_next_indicator=1
        section.write(0x00) // section_number
        section.write(0x00) // last_section_number
        section.write(bodyBytes)

        val withoutCrc = section.toByteArray()
        val crc = crc32Mpeg(withoutCrc)
        val full = ByteArrayOutputStream()
        full.write(withoutCrc)
        full.write32(crc)

        emitSection(PAT_PID, full.toByteArray())
    }

    // ---------------------------------------------------------------------
    // PMT
    // ---------------------------------------------------------------------

    private fun writePmt() {
        val body = ByteArrayOutputStream()
        body.write16(0xE000 or VIDEO_PID) // reserved + PCR_PID (video carries its own PCR)
        body.write16(0xF000) // reserved + program_info_length = 0

        // Single elementary stream: H.264 video.
        body.write(STREAM_TYPE_H264)
        body.write16(0xE000 or VIDEO_PID)
        body.write16(0xF000) // ES_info_length = 0

        val bodyBytes = body.toByteArray()
        val sectionLength = 5 + bodyBytes.size + 4

        val section = ByteArrayOutputStream()
        section.write(0x02) // table_id: TS_program_map_section
        section.write16(0xB000 or sectionLength)
        section.write16(PROGRAM_NUMBER)
        section.write(0xC1)
        section.write(0x00)
        section.write(0x00)
        section.write(bodyBytes)

        val withoutCrc = section.toByteArray()
        val crc = crc32Mpeg(withoutCrc)
        val full = ByteArrayOutputStream()
        full.write(withoutCrc)
        full.write32(crc)

        emitSection(PMT_PID, full.toByteArray())
    }

    /** Wraps a PSI section (PAT/PMT) body, including the pointer_field, into one or more TS packets. */
    private fun emitSection(pid: Int, sectionBytes: ByteArray) {
        val payload = ByteArrayOutputStream()
        payload.write(0x00) // pointer_field = 0 (section starts immediately)
        payload.write(sectionBytes)
        writePayloadAsTsPackets(pid, payload.toByteArray(), payloadUnitStart = true, pcr = null)
    }

    // ---------------------------------------------------------------------
    // PES
    // ---------------------------------------------------------------------

    private fun buildPesPacket(payload: ByteArray, pts90k: Long): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 19)
        out.write(0x00); out.write(0x00); out.write(0x01) // packet_start_code_prefix
        out.write(0xE0) // stream_id: video stream 0
        // PES_packet_length = 0 is legal for video elementary streams whose
        // length exceeds 0xFFFF or is otherwise not known in advance.
        out.write(0x00); out.write(0x00)
        out.write(0x80) // '10' + flags (no scrambling/priority/alignment/copyright/original)
        out.write(0x80) // PTS_DTS_flags = '10' (PTS only), rest 0
        out.write(0x05) // PES_header_data_length = 5 (PTS only)
        writePts(out, 0x2, pts90k) // '0010' marker for PTS-only
        out.write(payload)
        return out.toByteArray()
    }

    private fun writePts(out: ByteArrayOutputStream, prefix: Int, pts: Long) {
        val p = pts and 0x1FFFFFFFFL
        val b0 = ((prefix shl 4) or (((p shr 30) and 0x7).toInt() shl 1) or 1)
        val b1 = ((p shr 22) and 0xFF).toInt()
        val b2 = ((((p shr 15) and 0x7F).toInt() shl 1) or 1)
        val b3 = ((p shr 7) and 0xFF).toInt()
        val b4 = ((((p) and 0x7F).toInt() shl 1) or 1)
        out.write(b0); out.write(b1); out.write(b2); out.write(b3); out.write(b4)
    }

    private fun writePesAsTsPackets(pes: ByteArray, pts90k: Long, carryPcr: Boolean) {
        writePayloadAsTsPackets(
            VIDEO_PID,
            pes,
            payloadUnitStart = true,
            pcr = if (carryPcr) pts90k * 300L else null // PCR base in 27MHz units (base*300)
        )
    }

    // ---------------------------------------------------------------------
    // Generic TS packetization
    // ---------------------------------------------------------------------

    private fun writePayloadAsTsPackets(pid: Int, data: ByteArray, payloadUnitStart: Boolean, pcr: Long?) {
        var offset = 0
        var first = true
        while (offset < data.size) {
            val cc = nextContinuityCounter(pid)
            val packet = ByteArray(TS_PACKET_SIZE)
            var pos = 0

            packet[pos++] = SYNC_BYTE.toByte()
            val pusi = if (first && payloadUnitStart) 0x40 else 0x00
            packet[pos++] = (pusi or ((pid shr 8) and 0x1F)).toByte()
            packet[pos++] = (pid and 0xFF).toByte()

            val remaining = data.size - offset
            val headerReservedForAdaptation = if (first && pcr != null) 8 else 0
            val maxPayloadNoAdaptation = TS_PACKET_SIZE - 4
            val needsStuffing = remaining < (maxPayloadNoAdaptation - headerReservedForAdaptation)

            var adaptationFieldControl: Int
            var payloadLenAvailable: Int

            if (first && pcr != null) {
                // Adaptation field carrying PCR, then payload.
                adaptationFieldControl = 0x30 // adaptation field + payload
                val afLenWithoutStuffing = 1 /*flags*/ + 6 /*PCR*/
                val spaceForPayload = maxPayloadNoAdaptation - (1 + afLenWithoutStuffing)
                val stuffing = if (remaining < spaceForPayload) (spaceForPayload - remaining) else 0
                val afLength = afLenWithoutStuffing + stuffing

                packet[pos++] = (adaptationFieldControl or cc).toByte()
                packet[pos++] = afLength.toByte()
                val afFlags = 0x10 // PCR_flag = 1
                packet[pos++] = afFlags.toByte()
                writePcr(packet, pos, pcr); pos += 6
                repeat(stuffing) { packet[pos++] = 0xFF.toByte() }

                payloadLenAvailable = TS_PACKET_SIZE - pos
            } else if (needsStuffing) {
                // Last packet of this payload and it doesn't fill 184 bytes:
                // pad with an adaptation field stuffing so total is exactly 188.
                adaptationFieldControl = 0x30
                pos = 3
                packet[pos++] = (adaptationFieldControl or cc).toByte()
                val spaceForPayload = remaining
                val afLength = (maxPayloadNoAdaptation - spaceForPayload) - 1
                packet[pos++] = afLength.toByte()
                if (afLength > 0) {
                    packet[pos++] = 0x00 // no flags set
                    repeat(afLength - 1) { packet[pos++] = 0xFF.toByte() }
                }
                payloadLenAvailable = TS_PACKET_SIZE - pos
            } else {
                adaptationFieldControl = 0x10 // payload only
                pos = 3
                packet[pos++] = (adaptationFieldControl or cc).toByte()
                payloadLenAvailable = TS_PACKET_SIZE - pos
            }

            val toCopy = minOf(payloadLenAvailable, remaining)
            System.arraycopy(data, offset, packet, pos, toCopy)
            pos += toCopy
            offset += toCopy

            // Should always land exactly on TS_PACKET_SIZE; fill any stray gap defensively.
            while (pos < TS_PACKET_SIZE) packet[pos++] = 0xFF.toByte()

            onPacket(packet)
            first = false
        }
    }

    private fun writePcr(buf: ByteArray, offset: Int, pcr27M: Long) {
        val base = (pcr27M / 300) and 0x1FFFFFFFFL
        val ext = (pcr27M % 300) and 0x1FF
        buf[offset] = ((base shr 25) and 0xFF).toByte()
        buf[offset + 1] = ((base shr 17) and 0xFF).toByte()
        buf[offset + 2] = ((base shr 9) and 0xFF).toByte()
        buf[offset + 3] = ((base shr 1) and 0xFF).toByte()
        buf[offset + 4] = (((base and 0x1).toInt() shl 7) or 0x7E or (((ext shr 8) and 0x1).toInt())).toByte()
        buf[offset + 5] = (ext and 0xFF).toByte()
    }

    private fun nextContinuityCounter(pid: Int): Int {
        val counter = continuityCounters.getOrPut(pid) { AtomicInteger(0) }
        val value = counter.getAndUpdate { (it + 1) and 0x0F }
        return value
    }

    private fun ptsFromUs(us: Long): Long = (us * PTS_CLOCK_HZ) / 1_000_000L

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun ByteArrayOutputStream.write16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.write32(value: Long) {
        write(((value shr 24) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    /** Standard MPEG-2 CRC32 (poly 0x04C11DB7) used by PSI sections. */
    private fun crc32Mpeg(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor ((b.toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if ((crc and 0x80000000L) != 0L) {
                    (crc shl 1) xor 0x04C11DB7L
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFFFFFFL
            }
        }
        return crc
    }
}
