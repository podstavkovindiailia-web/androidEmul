package io.androidemul.scrcpy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.androidemul.stream.ClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientMessageTest {
    private val mapper = jacksonObjectMapper()
    private fun parse(json: String) = mapper.readValue(json, ClientMessage::class.java)

    @Test
    fun `touch json maps to inject touch`() {
        val msg = parse("""{"type":"touch","action":2,"pointerId":3,"x":5000,"y":-4,"width":720,"height":1280,"pressure":0.5}""")
        val control = msg.toControlMessage() as ControlMessage.InjectTouch
        assertEquals(719, control.x) // clamped
        assertEquals(0, control.y)
        assertEquals(720, control.screenWidth)
        assertEquals(0.5f, control.pressure)
    }

    @Test
    fun `touch up has zero pressure`() {
        val control = parse("""{"type":"touch","action":1,"pointerId":1,"x":1,"y":1,"width":10,"height":10}""").toControlMessage()
        assertEquals(0f, (control as ControlMessage.InjectTouch).pressure)
    }

    @Test
    fun `invalid values are rejected`() {
        assertNull(parse("""{"type":"touch","action":9,"pointerId":1,"x":1,"y":1,"width":10,"height":10}""").toControlMessage())
        assertNull(parse("""{"type":"touch","action":0,"pointerId":-1,"x":1,"y":1,"width":10,"height":10}""").toControlMessage())
        assertNull(parse("""{"type":"command","name":"rm -rf"}""").toControlMessage())
    }

    @Test
    fun `commands and keys`() {
        assertEquals(ControlMessage.Simple.ROTATE_DEVICE, parse("""{"type":"command","name":"rotate"}""").toControlMessage())
        assertEquals(ControlMessage.InjectKeycode(0, 3), parse("""{"type":"key","action":0,"keycode":3}""").toControlMessage())
    }
}
