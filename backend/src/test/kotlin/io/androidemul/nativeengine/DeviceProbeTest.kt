package io.androidemul.nativeengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceProbeTest {
    private val emulatorGetevent = """
        add device 1: /dev/input/event3
          bus:      0006
          vendor    0000
          product   0000
          version   0000
          name:     "virtio_input_multi_touch_2"
          location: "virtio25/input0"
          id:       ""
          version:  1.0.1
          events:
            KEY (0001): BTN_TOUCH
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID    : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
        add device 2: /dev/input/event1
          name:     "virtio_input_multi_touch_1"
          events:
            KEY (0001): BTN_TOUCH
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 2147483647, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID    : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_PRESSURE       : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
        add device 3: /dev/input/event0
          name:     "qwerty2"
          events:
            KEY (0001): KEY_ESC KEY_1 KEY_2
          input props:
            <none>
    """.trimIndent()

    @Test
    fun `picks the first direct multi-touch device`() {
        val d = DeviceProbe.parseTouchDevice(emulatorGetevent)!!
        assertEquals("/dev/input/event1", d.path)
        assertEquals("virtio_input_multi_touch_1", d.name)
        assertEquals(true, d.multiTouch)
        assertEquals(AxisRange(0, 32767), d.x)
        assertEquals(9, d.maxSlot)
        assertEquals(AxisRange(0, 255), d.pressure)
        assertEquals(true, d.hasBtnTouch)
    }

    @Test
    fun `no touch device`() {
        assertNull(DeviceProbe.parseTouchDevice("add device 1: /dev/input/event0\n  name:     \"qwerty2\"\n"))
    }

    @Test
    fun `display size prefers override`() {
        assertEquals(DisplaySize(1080, 2400), DeviceProbe.parseDisplaySize("Physical size: 1080x2400\n"))
        assertEquals(DisplaySize(720, 1600), DeviceProbe.parseDisplaySize("Physical size: 1080x2400\nOverride size: 720x1600\n"))
        assertEquals(DisplaySize(2400, 1080), DisplaySize(1080, 2400).rotated(1))
    }

    @Test
    fun `rotation from dumpsys`() {
        assertEquals(1, DeviceProbe.parseRotation("      SurfaceOrientation: 1\n"))
        assertEquals(3, DeviceProbe.parseRotation("Viewport INTERNAL: displayId=0, uniqueId=local:1, port=0, orientation=3, logicalFrame=[0, 0, 2400, 1080]"))
        assertEquals(2, DeviceProbe.parseRotation("  mRotation=2 mDeferredRotationPauseCount=0"))
        assertNull(DeviceProbe.parseRotation(""))
    }

    @Test
    fun `video size fits max size in multiples of 8`() {
        assertEquals(DisplaySize(576, 1280), DeviceProbe.videoSize(DisplaySize(1080, 2400), 1280))
        assertEquals(DisplaySize(720, 1280), DeviceProbe.videoSize(DisplaySize(720, 1280), 1280))
        assertEquals(DisplaySize(1080, 2400), DeviceProbe.videoSize(DisplaySize(1080, 2400), 0))
    }
}
