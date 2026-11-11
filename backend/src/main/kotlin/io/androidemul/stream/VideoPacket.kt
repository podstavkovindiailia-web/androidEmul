package io.androidemul.stream

/** One packet of the H.264 (Annex B) video stream: codec config (SPS/PPS) or one frame. */
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
    }
}
