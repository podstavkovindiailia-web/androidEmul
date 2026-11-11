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

    private val dumpsysInput = """
        Event Hub State:
          Devices:
            4: virtio_input_multi_touch_2
              Classes: TOUCH | TOUCH_MT
              Path: /dev/input/event3
              Enabled: true
              Descriptor: abc
            5: virtio_input_multi_touch_1
              Classes: TOUCH | TOUCH_MT
              Path: /dev/input/event1
              Enabled: false
              Descriptor: def
        Input Reader State (Nums of device: 2):
          Device 4: virtio_input_multi_touch_2
            EventHub Devices: [ 4 ]
            Touch Input Mapper (mode - DIRECT):
              Viewport INTERNAL: displayId=0, uniqueId=local:1, port=0, orientation=0, logicalFrame=[0, 0, 1080, 2400]
          Device 5: virtio_input_multi_touch_1
            EventHub Devices: [ 5 ]
            Touch Input Mapper (mode - DIRECT):
              Viewport: <none>
    """.trimIndent()

    @Test
    fun `dumpsys input decides between the emulator's virtual touchscreens`() {
        assertEquals(mapOf("/dev/input/event3" to true, "/dev/input/event1" to false), DeviceProbe.parseEnabledStates(dumpsysInput))
        assertEquals(setOf("virtio_input_multi_touch_2"), DeviceProbe.parseDisplayBoundDevices(dumpsysInput))
        val ranked = DeviceProbe.rankTouchDevices(emulatorGetevent, dumpsysInput)
        assertEquals(listOf("/dev/input/event3"), ranked.map { it.path }) // event1 is disabled
    }

    @Test
    fun `without dumpsys the lowest numbered virtual touchscreen wins`() {
        val ranked = DeviceProbe.rankTouchDevices(emulatorGetevent, "")
        assertEquals(listOf("virtio_input_multi_touch_1", "virtio_input_multi_touch_2"), ranked.map { it.name })
        assertEquals(AxisRange(0, 9), ranked.first().trackingId)
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
        // the default display's viewport wins over other orientation lines
        assertEquals(1, DeviceProbe.parseRotation("Viewport VIRTUAL: displayId=2, orientation=0\nViewport INTERNAL: displayId=0, uniqueId=local:1, port=0, orientation=1, logicalFrame=[]"))
        assertNull(DeviceProbe.parseRotation(""))
    }

    @Test
    fun `video size fits max size in multiples of 8`() {
        assertEquals(DisplaySize(576, 1280), DeviceProbe.videoSize(DisplaySize(1080, 2400), 1280))
        assertEquals(DisplaySize(720, 1280), DeviceProbe.videoSize(DisplaySize(720, 1280), 1280))
        assertEquals(DisplaySize(1080, 2400), DeviceProbe.videoSize(DisplaySize(1080, 2400), 0))
    }
}
