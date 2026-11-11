package io.androidemul.stream

/** One packet of the scrcpy video stream (H.264 Annex B). */
class VideoPacket(
    val config: Boolean,
    val keyFrame: Boolean,
    /** Presentation timestamp in microseconds (0 for config packets). */
    val pts: Long,
    val data: ByteArray,
) {
    /**
     * Wire format sent to browsers: [flags:1][pts:8 BE][data...].
     * flags: bit0 = config (SPS/PPS), bit1 = key frame.
     */
    fun toWireFormat(): ByteArray {
        val out = ByteArray(HEADER_SIZE + data.size)
        out[0] = ((if (config) FLAG_CONFIG else 0) or (if (keyFrame) FLAG_KEY_FRAME else 0)).toByte()
        var v = pts
        for (i in 8 downTo 1) {
            out[i] = (v and 0xff).toByte()
            v = v ushr 8
        }
        System.arraycopy(data, 0, out, HEADER_SIZE, data.size)
        return out
    }

    companion object {
        const val HEADER_SIZE = 9
        const val FLAG_CONFIG = 1
        const val FLAG_KEY_FRAME = 2

        // scrcpy frame header (12 bytes): [pts_and_flags:8][size:4]
        const val SCRCPY_FLAG_CONFIG = 1L shl 63
        const val SCRCPY_FLAG_KEY_FRAME = 1L shl 62
        const val SCRCPY_PTS_MASK = SCRCPY_FLAG_KEY_FRAME - 1

        fun fromScrcpyHeader(ptsAndFlags: Long, data: ByteArray): VideoPacket {
            val config = (ptsAndFlags and SCRCPY_FLAG_CONFIG) != 0L
            val key = (ptsAndFlags and SCRCPY_FLAG_KEY_FRAME) != 0L
            return VideoPacket(config, key, if (config) 0 else ptsAndFlags and SCRCPY_PTS_MASK, data)
        }
    }
}
