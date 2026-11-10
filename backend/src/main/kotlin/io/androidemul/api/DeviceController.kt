package io.androidemul.api

import io.androidemul.adb.AdbClient
import io.androidemul.adb.AdbDevice
import io.androidemul.adb.AdbException
import io.androidemul.config.EmulatorProperties
import io.androidemul.scrcpy.ScrcpySessionManager
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class DeviceDto(val serial: String, val state: String, val model: String?, val emulator: Boolean, val streaming: Boolean)

@RestController
@RequestMapping("/api")
class DeviceController(
    private val adb: AdbClient,
    private val manager: ScrcpySessionManager,
    private val props: EmulatorProperties,
) {
    @GetMapping("/devices")
    fun devices(): List<DeviceDto> {
        val active = manager.activeSessions()
        return adb.devices().map { d: AdbDevice ->
            DeviceDto(d.serial, d.state, d.model?.replace('_', ' '), d.emulator, active.containsKey(d.serial))
        }
    }

    /** Connect a network device / emulator, e.g. address=10.0.2.2:5555 */
    @PostMapping("/devices/connect")
    fun connect(@RequestParam address: String): Map<String, String> {
        require(Regex("^[A-Za-z0-9.\\-]+:\\d{1,5}$").matches(address)) { "Expected host:port" }
        return mapOf("result" to adb.connect(address))
    }

    @GetMapping("/config")
    fun config() = props.stream

    @ExceptionHandler(AdbException::class)
    fun adbError(e: AdbException) = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to e.message))

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
