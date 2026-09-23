package com.camip.app.encoder

/**
 * Splits an Annex-B H.264 byte buffer into individual NAL units by scanning
 * for start codes, rather than assuming the first byte of a MediaCodec
 * output buffer is already a NAL header.
 *
 * Both 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes are handled,
 * and each returned NAL unit retains its own leading start code so the
 * result can be safely reassembled or passed straight into an MPEG-TS PES
 * payload without any reformatting.
 */
object NalUnitParser {

    /** Returns a list of NAL units, each still prefixed by its Annex-B start code. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = findStartCodeOffsets(data)
        if (starts.isEmpty()) return emptyList()

        val result = ArrayList<ByteArray>(starts.size)
        for (i in starts.indices) {
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else data.size
            if (end > start) {
                result.add(data.copyOfRange(start, end))
            }
        }
        return result
    }

    /** Returns byte offsets where each NAL unit (including its start code) begins. */
    private fun findStartCodeOffsets(data: ByteArray): List<Int> {
        val offsets = ArrayList<Int>()
        var i = 0
        val limit = data.size - 3
        while (i <= limit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    offsets.add(i)
                    i += 3
                    continue
                } else if (i <= data.size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    offsets.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }
        return offsets
    }

    /**
     * Extracts the NAL unit type (bits 3..7 of the byte immediately after
     * the start code), handling both 3- and 4-byte start-code prefixes.
     */
    fun nalType(nalWithStartCode: ByteArray): Int {
        val headerOffset = startCodeLength(nalWithStartCode)
        if (headerOffset < 0 || headerOffset >= nalWithStartCode.size) return -1
        return nalWithStartCode[headerOffset].toInt() and 0x1F
    }

    private fun startCodeLength(nal: ByteArray): Int {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
            return 4
        }
        if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
            return 3
        }
        return -1
    }
}
