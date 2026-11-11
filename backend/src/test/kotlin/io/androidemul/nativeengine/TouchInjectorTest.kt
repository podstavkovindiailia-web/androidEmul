package io.androidemul.nativeengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchInjectorTest {
    private val device = TouchDevice(
        path = "/dev/input/event1", name = "ts", multiTouch = true,
        x = AxisRange(0, 1000), y = AxisRange(0, 2000), maxSlot = 9,
        pressure = AxisRange(0, 255), touchMajor = null, hasBtnTouch = true,
    )
    private fun ev(type: Int, code: Int, value: Int) = "sendevent /dev/input/event1 $type $code $value"

    @Test
    fun `multi-touch protocol B sequence`() {
        val inj = TouchInjector(device)
        assertEquals(
            listOf(ev(3, 0x2f, 0), ev(3, 0x39, 1), ev(3, 0x35, 500), ev(3, 0x36, 1000), ev(3, 0x3a, 127), ev(1, 0x14a, 1), ev(0, 0, 0)),
            inj.down(0, 0.5, 0.5, 0),
        )
        // second finger: no BTN_TOUCH
        assertTrue(inj.down(1, 0.1, 0.1, 0).none { it.contains(" 1 330 ") })
        assertEquals(listOf(ev(3, 0x2f, 0), ev(3, 0x35, 1000), ev(3, 0x36, 2000), ev(0, 0, 0)), inj.move(0, 1.0, 1.0, 0))
        assertEquals(listOf(ev(3, 0x2f, 0), ev(3, 0x39, -1), ev(0, 0, 0)), inj.up(0))
        // last finger up releases BTN_TOUCH
        assertEquals(listOf(ev(3, 0x2f, 1), ev(3, 0x39, -1), ev(1, 0x14a, 0), ev(0, 0, 0)), inj.up(1))
        assertEquals(emptyList(), inj.move(0, 0.2, 0.2, 0))
    }

    @Test
    fun `rotation maps display coordinates to the panel`() {
        val inj = TouchInjector(device)
        // top-left of the display
        assertEquals(0 to 0, inj.toRaw(0.0, 0.0, 0))
        assertEquals(1000 to 0, inj.toRaw(0.0, 0.0, 1)) // ROTATION_90: display (0,0) = panel top-right
        assertEquals(1000 to 2000, inj.toRaw(0.0, 0.0, 2))
        assertEquals(0 to 2000, inj.toRaw(0.0, 0.0, 3))
        assertEquals(500 to 1000, inj.toRaw(0.5, 0.5, 1))
    }

    @Test
    fun `moves are coalesced per finger but never across down or up`() {
        val q = mutableListOf<TouchOp>(TouchOp.Down(0, 0.0, 0.0), TouchOp.Move(0, 0.1, 0.1), TouchOp.Move(1, 0.5, 0.5))
        assertTrue(TouchOp.merge(q, TouchOp.Move(0, 0.2, 0.2)))
        assertEquals(TouchOp.Move(0, 0.2, 0.2), q[1])
        q += TouchOp.Up(0)
        assertEquals(false, TouchOp.merge(q, TouchOp.Move(1, 0.6, 0.6)))
    }

    @Test
    fun `commands are merged and escaped`() {
        val q = mutableListOf<CommandOp>(CommandOp.Text("hi"))
        assertTrue(CommandOp.merge(q, CommandOp.Text(" there")))
        assertEquals("input text 'hi%sthere'", CommandOp.render(q.single(), DisplaySize(100, 100)))
        assertEquals("input text 'it'\\''s'", CommandOp.render(CommandOp.Text("it's"), DisplaySize(100, 100)))
        assertEquals("input text 'ok'", CommandOp.render(CommandOp.Text("ok привет"), DisplaySize(100, 100)).replace("%s", ""))
        val keys = mutableListOf<CommandOp>(CommandOp.Keys(listOf(66)))
        assertTrue(CommandOp.merge(keys, CommandOp.Keys(listOf(67))))
        assertEquals("input keyevent 66 67", CommandOp.render(keys.single(), DisplaySize(100, 100)))
    }
}
