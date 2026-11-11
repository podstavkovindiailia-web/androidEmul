package io.androidemul.nativeengine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnexBParserTest {
    private data class Packet(val config: Boolean, val key: Boolean, val data: List<Byte>)

    private fun nal(vararg bytes: Int, fourByteStartCode: Boolean = true): ByteArray =
        (if (fourByteStartCode) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)) + bytes.map { it.toByte() }.toByteArray()

    private val sps = nal(0x67, 0x64, 0x00, 0x1f, 0xac)
    private val pps = nal(0x68, 0xee, 0x3c, 0x80)
    private val sei = nal(0x06, 0x05, 0x11)
    private val idr = nal(0x65, 0x88, 0x84, 0x21)
    private val p1 = nal(0x41, 0x9a, 0x22, 0x33, fourByteStartCode = false)
    private val p2SliceA = nal(0x41, 0x9a, 0x44)
    private val p2SliceB = nal(0x41, 0x40, 0x55) // first_mb_in_slice != 0: same picture

    private fun parse(chunks: List<ByteArray>, flushAfterEach: Boolean): List<Packet> {
        val out = mutableListOf<Packet>()
        val parser = AnnexBParser { config, key, data -> out += Packet(config, key, data.toList()) }
        for (c in chunks) {
            parser.feed(c)
            if (flushAfterEach) parser.flush()
        }
        parser.flush()
        return out
    }

    @Test
    fun `splits config, key frame and multi-slice frames`() {
        val stream = sps + pps + sei + idr + p1 + p2SliceA + p2SliceB
        val packets = parse(listOf(stream), flushAfterEach = false)
        assertEquals(4, packets.size)
        assertEquals(Packet(true, false, (sps + pps).toList()), packets[0])
        assertEquals(Packet(false, true, (sei + idr).toList()), packets[1])
        assertEquals(Packet(false, false, p1.toList()), packets[2])
        assertEquals(Packet(false, false, (p2SliceA + p2SliceB).toList()), packets[3])
    }

    @Test
    fun `byte by byte feeding gives the same result`() {
        val stream = sps + pps + idr + p1
        val chunks = stream.map { byteArrayOf(it) }
        val packets = parse(chunks, flushAfterEach = false)
        assertEquals(3, packets.size)
        assertContentEquals((sps + pps), packets[0].data.toByteArray())
        assertTrue(packets[1].key)
        assertContentEquals(p1, packets[2].data.toByteArray())
    }

    @Test
    fun `flush on idle emits the frame immediately`() {
        // screenrecord style: one write per encoder buffer, the stream then goes silent
        val packets = parse(listOf(sps + pps, idr, p1), flushAfterEach = true)
        assertEquals(listOf(true, false, false), packets.map { it.config })
        assertContentEquals(idr, packets[1].data.toByteArray())
        assertContentEquals(p1, packets[2].data.toByteArray())
    }

    @Test
    fun `text before the first start code is collected as junk`() {
        val out = mutableListOf<Packet>()
        val parser = AnnexBParser { c, k, d -> out += Packet(c, k, d.toList()) }
        parser.feed("ERROR: time limit 0s out of range\n".toByteArray())
        parser.flush()
        assertTrue(out.isEmpty())
        assertEquals(0, parser.nalCount)
        assertTrue(parser.junk.contains("time limit"))
    }
}
