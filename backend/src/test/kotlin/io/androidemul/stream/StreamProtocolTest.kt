package io.androidemul.stream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamProtocolTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `video packet wire format`() {
        assertEquals("02" + "0000000000000102" + "0908", hex(VideoPacket(false, true, 0x102, byteArrayOf(9, 8)).toWireFormat()))
        assertEquals("01" + "0000000000000000" + "01", hex(VideoPacket(true, false, 0, byteArrayOf(1)).toWireFormat()))
    }

    @Test
    fun `client messages are parsed by type`() {
        val mapper = jacksonObjectMapper()
        fun parse(json: String) = mapper.readValue(json, ClientMessage::class.java)
        assertEquals(
            ClientMessage.Touch(action = 2, pointerId = 3, x = 10, y = 20, width = 720, height = 1280, pressure = 0.5f),
            parse("""{"type":"touch","action":2,"pointerId":3,"x":10,"y":20,"width":720,"height":1280,"pressure":0.5}"""),
        )
        assertEquals(ClientMessage.Key(0, 3), parse("""{"type":"key","action":0,"keycode":3}"""))
        assertEquals(ClientMessage.Text("hi"), parse("""{"type":"text","text":"hi"}"""))
        assertEquals(ClientMessage.Command("rotate"), parse("""{"type":"command","name":"rotate"}"""))
        assertEquals(
            ClientMessage.Scroll(1, 2, 720, 1280, 0f, -1f),
            parse("""{"type":"scroll","x":1,"y":2,"width":720,"height":1280,"hScroll":0,"vScroll":-1}"""),
        )
    }
}
