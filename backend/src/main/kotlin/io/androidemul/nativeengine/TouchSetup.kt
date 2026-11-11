package io.androidemul.nativeengine

import io.androidemul.adb.AdbClient
import org.slf4j.Logger

enum class TouchMode(val label: String) {
    /** Raw evdev events into the touchscreen node: live, multi-touch. */
    SENDEVENT("sendevent"),
    /** `input motionevent DOWN|MOVE|UP` (Android 10+): live drag, single finger, slower. */
    MOTIONEVENT("input motionevent"),
    /** `input tap` / `input swipe`, replayed when the finger lifts. */
    TAP_SWIPE("input tap/swipe"),
}

data class TouchPlan(val mode: TouchMode, val device: TouchDevice?, val reason: String)

/** Finds out how touches can be injected on this device. */
class TouchSetup(private val adb: AdbClient, private val serial: String, private val log: Logger) {

    fun candidates(): List<TouchDevice> {
        val getevent = runCatching { adb.shellOutput(serial, "getevent -lp") }.getOrDefault("")
        val dumpsys = runCatching { adb.shellOutput(serial, "dumpsys input") }.getOrDefault("")
        return DeviceProbe.rankTouchDevices(getevent, dumpsys)
    }

    /** Tries sendevent on the best candidates (a harmless SYN_REPORT), then falls back to `input`. */
    fun plan(shell: CommandShell): TouchPlan {
        val candidates = candidates()
        val errors = mutableListOf<String>()
        for (device in candidates.take(3)) {
            val result = shell.run(listOf("sendevent ${device.path} 0 0 0; echo rc=$?"), timeoutMs = 5000)
            if (result.ok && result.output.any { it.trim() == "rc=0" }) {
                return TouchPlan(TouchMode.SENDEVENT, device, "sendevent works on ${device.path}")
            }
            errors += "${device.path}: ${result.text.ifBlank { "no answer" }}"
        }
        val reason = if (candidates.isEmpty()) "no touchscreen found by getevent" else "sendevent failed (${errors.joinToString("; ")})"
        val inputHelp = runCatching { adb.shellOutput(serial, "input 2>&1; true") }.getOrDefault("")
        return if (inputHelp.contains("motionevent")) {
            TouchPlan(TouchMode.MOTIONEVENT, null, reason)
        } else {
            TouchPlan(TouchMode.TAP_SWIPE, null, reason)
        }
    }

    /** Everything needed to debug touch input on a device (served by /api/devices/{serial}/diagnostics). */
    fun diagnostics(): Map<String, Any?> {
        fun sh(cmd: String) = runCatching { adb.shellOutput(serial, cmd) }.getOrElse { "error: ${it.message}" }
        val getevent = sh("getevent -lp")
        val dumpsys = sh("dumpsys input")
        val ranked = DeviceProbe.rankTouchDevices(getevent, dumpsys)
        return linkedMapOf(
            "sdk" to sh("getprop ro.build.version.sdk").trim(),
            "build" to sh("getprop ro.build.type").trim(),
            "wmSize" to sh("wm size").trim(),
            "rotation" to DeviceProbe.parseRotation(dumpsys),
            "touchCandidates" to ranked,
            "enabledStates" to DeviceProbe.parseEnabledStates(dumpsys),
            "displayBoundDevices" to DeviceProbe.parseDisplayBoundDevices(dumpsys),
            "sendeventTest" to ranked.take(3).associate { it.path to sh("sendevent ${it.path} 0 0 0 2>&1; echo rc=$?").trim() },
            "id" to sh("id").trim(),
            "inputSupportsMotionevent" to sh("input 2>&1; true").contains("motionevent"),
            "getevent" to getevent,
        )
    }
}
