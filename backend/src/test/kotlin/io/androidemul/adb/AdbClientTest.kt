package io.androidemul.adb

import kotlin.test.Test
import kotlin.test.assertEquals

class AdbClientTest {
    @Test
    fun `parses adb devices -l output`() {
        val output = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
            192.168.1.5:5555       offline transport_id:2
            R58M123ABC             unauthorized usb:1-1 transport_id:3
        """.trimIndent()
        val devices = AdbClient.parseDevices(output)
        assertEquals(3, devices.size)
        assertEquals("emulator-5554", devices[0].serial)
        assertEquals("device", devices[0].state)
        assertEquals("sdk_gphone64_x86_64", devices[0].model)
        assertEquals(true, devices[0].emulator)
        assertEquals("offline", devices[1].state)
        assertEquals("unauthorized", devices[2].state)
    }
}
