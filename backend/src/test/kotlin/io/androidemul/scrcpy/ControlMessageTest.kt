package io.androidemul.scrcpy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ControlMessageTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `touch event matches scrcpy layout`() {
        val bytes = ControlMessage.InjectTouch(
            action = 0, pointerId = 7, x = 100, y = 200, screenWidth = 1080, screenHeight = 1920, pressure = 1f,
        ).serialize()
        assertEquals(32, bytes.size)
        assertEquals(
            "02" + "00" + "0000000000000007" + "00000064" + "000000c8" + "0438" + "0780" + "ffff" + "00000000" + "00000000",
            hex(bytes),
        )
    }

    @Test
    fun `keycode event matches scrcpy layout`() {
        val bytes = ControlMessage.InjectKeycode(action = 1, keycode = 4, repeat = 0, metaState = 0x1000).serialize()
        assertEquals("00" + "01" + "00000004" + "00000000" + "00001000", hex(bytes))
    }

    @Test
    fun `scroll event matches scrcpy layout`() {
        val bytes = ControlMessage.InjectScroll(10, 20, 720, 1280, hScroll = 0f, vScroll = -1f).serialize()
        assertEquals(21, bytes.size)
        assertEquals("03" + "0000000a" + "00000014" + "02d0" + "0500" + "0000" + "8000" + "00000000", hex(bytes))
    }

    @Test
    fun `text is length prefixed and truncated on utf8 boundary`() {
        assertEquals("01" + "00000002" + "6869", hex(ControlMessage.InjectText("hi").serialize()))
        val long = "я".repeat(200) // 400 bytes
        val bytes = ControlMessage.InjectText(long).serialize()
        assertEquals(1 + 4 + 300, bytes.size)
    }

    @Test
    fun `simple messages`() {
        assertContentEquals(byteArrayOf(17), ControlMessage.Simple.RESET_VIDEO.serialize())
        assertContentEquals(byteArrayOf(4, 0), ControlMessage.BackOrScreenOn(0).serialize())
    }

    @Test
    fun `fixed point conversions`() {
        assertEquals(0xffff.toShort(), ControlMessage.floatToU16FixedPoint(1f))
        assertEquals(0x8000.toShort(), ControlMessage.floatToU16FixedPoint(0.5f))
        assertEquals(0x7fff.toShort(), ControlMessage.floatToI16FixedPoint(1f))
        assertEquals((-0x8000).toShort(), ControlMessage.floatToI16FixedPoint(-2f))
    }

    @Test
    fun `video packet wire format`() {
        val raw = VideoPacket.fromScrcpyHeader(VideoPacket.SCRCPY_FLAG_KEY_FRAME or 0x0102L, byteArrayOf(9, 8))
        assertEquals(true, raw.keyFrame)
        assertEquals(false, raw.config)
        assertEquals("02" + "0000000000000102" + "0908", hex(raw.toWireFormat()))
        val config = VideoPacket.fromScrcpyHeader(VideoPacket.SCRCPY_FLAG_CONFIG, byteArrayOf(1))
        assertEquals("01" + "0000000000000000" + "01", hex(config.toWireFormat()))
    }
}
