package io.androidemul.nativeengine

import java.io.ByteArrayOutputStream

/**
 * Splits a raw H.264 Annex B byte stream (as written by `screenrecord --output-format=h264`) into packets:
 * a config packet (SPS + PPS) and one packet per access unit (frame), flagged as key frame when it contains an IDR slice.
 *
 * NAL boundaries are only known when the next start code arrives, so the caller invokes [flush] when the stream goes idle:
 * screenrecord writes one encoder buffer (= one frame) at a time, so "idle" means "the frame is complete".
 * This avoids holding back the last frame of an animation until the screen changes again.
 */
class AnnexBParser(private val onPacket: (config: Boolean, keyFrame: Boolean, data: ByteArray) -> Unit) {
    private var buf = ByteArray(1 shl 16)
    private var len = 0
    /** Position up to which [buf] has been scanned for start codes. */
    private var scanPos = 0
    /** Start of the NAL currently being accumulated (index of its start code), -1 = none yet. */
    private var nalStart = -1

    private val config = ByteArrayOutputStream()
    private val au = ByteArrayOutputStream()
    private var auHasSlice = false
    private var auKey = false

    /** Bytes received before the first start code (usually an error message instead of video). */
    val junk = StringBuilder()
    var nalCount = 0L
        private set

    fun feed(data: ByteArray, off: Int = 0, length: Int = data.size) {
        ensureCapacity(len + length)
        System.arraycopy(data, off, buf, len, length)
        len += length
        scan()
    }

    /** Treats all buffered data as complete: emits the pending NAL and the current access unit. */
    fun flush() {
        if (nalStart < 0 && len > 0) {
            if (junk.length < 2048) junk.append(String(buf, 0, len, Charsets.UTF_8))
            compact(len)
        }
        if (nalStart >= 0 && len - nalStart > 3) {
            handleNal(nalStart, len)
            nalStart = -1
            compact(len)
        }
        emitConfig()
        emitAccessUnit()
    }

    private fun scan() {
        var i = scanPos
        while (i + 2 < len) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                // include a leading zero of a 4-byte start code
                val sc = if (i > 0 && buf[i - 1].toInt() == 0 && (nalStart < 0 || i - 1 > nalStart)) i - 1 else i
                if (nalStart >= 0) {
                    handleNal(nalStart, sc)
                } else if (sc > 0 && junk.length < 2048) {
                    junk.append(String(buf, 0, sc, Charsets.UTF_8))
                }
                nalStart = sc
                i += 3
                continue
            }
            i++
        }
        scanPos = i
        if (nalStart > 0) {
            compact(nalStart)
        } else if (nalStart < 0 && len > 4096) {
            if (junk.length < 2048) junk.append(String(buf, 0, len - 3, Charsets.UTF_8))
            compact(len - 3)
        }
    }

    /** Drops everything before [from]. */
    private fun compact(from: Int) {
        if (from <= 0) return
        System.arraycopy(buf, from, buf, 0, len - from)
        len -= from
        scanPos = maxOf(scanPos - from, 0)
        if (nalStart >= 0) nalStart -= from
    }

    private fun handleNal(start: Int, end: Int) {
        // header byte is right after the start code
        val headerPos = if (buf[start + 2].toInt() == 1) start + 3 else start + 4
        if (headerPos >= end) return
        nalCount++
        val type = buf[headerPos].toInt() and 0x1f
        when (type) {
            NAL_SPS, NAL_PPS -> {
                if (auHasSlice) emitAccessUnit()
                config.write(buf, start, end - start)
            }
            NAL_SLICE, NAL_IDR -> {
                // first_mb_in_slice is ue(v); its first bit is 1 iff the value is 0, i.e. this slice starts a new picture
                val firstSliceOfPicture = headerPos + 1 < end && (buf[headerPos + 1].toInt() and 0x80) != 0
                if (auHasSlice && firstSliceOfPicture) emitAccessUnit()
                emitConfig()
                au.write(buf, start, end - start)
                auHasSlice = true
                if (type == NAL_IDR) auKey = true
            }
            NAL_SEI, NAL_AUD -> {
                if (auHasSlice) emitAccessUnit()
                emitConfig()
                au.write(buf, start, end - start)
            }
            else -> au.write(buf, start, end - start)
        }
    }

    private fun emitConfig() {
        if (config.size() == 0) return
        onPacket(true, false, config.toByteArray())
        config.reset()
    }

    private fun emitAccessUnit() {
        if (!auHasSlice) return
        onPacket(false, auKey, au.toByteArray())
        au.reset()
        auHasSlice = false
        auKey = false
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buf.size) return
        var size = buf.size
        while (size < required) size *= 2
        buf = buf.copyOf(size)
    }

    private companion object {
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SEI = 6
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val NAL_AUD = 9
    }
}
