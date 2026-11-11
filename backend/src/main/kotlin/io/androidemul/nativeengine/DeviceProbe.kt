package io.androidemul.nativeengine

data class AxisRange(val min: Int, val max: Int)

/** A Linux evdev touch device found via `getevent -lp`. */
data class TouchDevice(
    val path: String,
    val name: String,
    /** Multi-touch protocol B (slots); false = single-touch ABS_X/ABS_Y device. */
    val multiTouch: Boolean,
    val x: AxisRange,
    val y: AxisRange,
    val maxSlot: Int,
    val pressure: AxisRange?,
    val touchMajor: AxisRange?,
    val hasBtnTouch: Boolean,
    val trackingId: AxisRange? = null,
)

/** Display size in its natural orientation (rotation 0). */
data class DisplaySize(val width: Int, val height: Int) {
    fun rotated(rotation: Int): DisplaySize = if (rotation % 2 == 1) DisplaySize(height, width) else this
}

/** Parsers for the output of standard Android shell tools. */
object DeviceProbe {
    private val deviceHeader = Regex("""^add device \d+:\s*(\S+)""", RegexOption.MULTILINE)
    private val nameLine = Regex("""name:\s*"([^"]*)"""")
    private val absLine = Regex("""(ABS_[A-Z0-9_]+)\s*:\s*value -?\d+, min (-?\d+), max (-?\d+)""")
    private val sizeLine = Regex("""(Physical|Override) size:\s*(\d+)x(\d+)""")
    private val orientationPatterns = listOf(
        Regex("""displayId=0\b[^\n]*?orientation=(\d)"""),
        Regex("""SurfaceOrientation:\s*(\d)"""),
        Regex("""orientation=(\d)"""),
        Regex("""mCurrentRotation=(?:ROTATION_)?(\d+)"""),
        Regex("""mRotation=(?:ROTATION_)?(\d+)"""),
    )

    /** Picks the most likely touchscreen from `getevent -lp` output alone. */
    fun parseTouchDevice(getevent: String): TouchDevice? = rankTouchDevices(getevent, "").firstOrNull()

    /**
     * All touch devices, best first. `dumpsys input` (may be empty) tells which devices Android has disabled and which
     * one is bound to the default display: the emulator creates up to 11 virtual touchscreens (one per possible display)
     * and only the one of display 0 accepts touches for the main screen.
     */
    fun rankTouchDevices(getevent: String, dumpsysInput: String): List<TouchDevice> {
        val headers = deviceHeader.findAll(getevent).toList()
        val devices = headers.mapIndexed { idx, header ->
            val end = if (idx + 1 < headers.size) headers[idx + 1].range.first else getevent.length
            parseBlock(header.groupValues[1], getevent.substring(header.range.first, end))
        }.filterNotNull()
        val enabled = parseEnabledStates(dumpsysInput)
        val bound = parseDisplayBoundDevices(dumpsysInput)
        return devices
            .filter { enabled[it.first.path] != false }
            .sortedWith(
                compareByDescending<Pair<TouchDevice, Int>> { it.first.name in bound }
                    .thenByDescending { it.second }
                    .thenBy { trailingNumber(it.first.name) }
                    .thenBy { trailingNumber(it.first.path) },
            )
            .map { it.first }
    }

    private val eventHubPath = Regex("""Path:\s*(/dev/input/\S+)""")
    private val enabledLine = Regex("""Enabled:\s*(true|false)""")
    private val readerDevice = Regex("""^\s*Device -?\d+:\s*(.+?)\s*$""", RegexOption.MULTILINE)
    private val displayZeroViewport = Regex("""Viewport[^\n]*displayId=0\b""")

    /** `dumpsys input` event hub section: device node path -> enabled. */
    fun parseEnabledStates(dumpsysInput: String): Map<String, Boolean> {
        val paths = eventHubPath.findAll(dumpsysInput).toList()
        return paths.mapIndexedNotNull { i, m ->
            val end = if (i + 1 < paths.size) paths[i + 1].range.first else minOf(dumpsysInput.length, m.range.last + 400)
            val enabled = enabledLine.find(dumpsysInput.substring(m.range.last, end))?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            m.groupValues[1] to (enabled == "true")
        }.toMap()
    }

    /** `dumpsys input` input reader section: names of devices that have a viewport on display 0. */
    fun parseDisplayBoundDevices(dumpsysInput: String): Set<String> {
        val headers = readerDevice.findAll(dumpsysInput).toList()
        return headers.mapIndexedNotNull { i, m ->
            val end = if (i + 1 < headers.size) headers[i + 1].range.first else dumpsysInput.length
            if (displayZeroViewport.containsMatchIn(dumpsysInput.substring(m.range.last, end))) m.groupValues[1] else null
        }.toSet()
    }

    private fun trailingNumber(s: String): Int = Regex("""(\d+)$""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    /** @return device and a preference score, or null if it is not a touch device. */
    private fun parseBlock(path: String, block: String): Pair<TouchDevice, Int>? {
        val name = nameLine.find(block)?.groupValues?.get(1).orEmpty()
        val abs = absLine.findAll(block).associate { it.groupValues[1] to AxisRange(it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        val direct = block.contains("INPUT_PROP_DIRECT")
        val btnTouch = block.contains("BTN_TOUCH")
        val mtX = abs["ABS_MT_POSITION_X"]
        val mtY = abs["ABS_MT_POSITION_Y"]
        val device = when {
            mtX != null && mtY != null -> TouchDevice(
                path, name, multiTouch = true, x = mtX, y = mtY,
                maxSlot = abs["ABS_MT_SLOT"]?.max ?: 0,
                pressure = abs["ABS_MT_PRESSURE"], touchMajor = abs["ABS_MT_TOUCH_MAJOR"], hasBtnTouch = btnTouch,
                trackingId = abs["ABS_MT_TRACKING_ID"],
            )
            abs["ABS_X"] != null && abs["ABS_Y"] != null && btnTouch && direct -> TouchDevice(
                path, name, multiTouch = false, x = abs.getValue("ABS_X"), y = abs.getValue("ABS_Y"),
                maxSlot = 0, pressure = abs["ABS_PRESSURE"], touchMajor = null, hasBtnTouch = true,
            )
            else -> return null
        }
        if (device.x.max <= device.x.min || device.y.max <= device.y.min) return null
        var score = 0
        if (direct) score += 4
        if (device.multiTouch) score += 2
        if (name.contains("touch", ignoreCase = true)) score += 1
        return device to score
    }

    /** `wm size`: the override size wins over the physical size. */
    fun parseDisplaySize(wmSize: String): DisplaySize? {
        val sizes = sizeLine.findAll(wmSize).associate { it.groupValues[1] to DisplaySize(it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        return sizes["Override"] ?: sizes["Physical"]
    }

    /** Display rotation 0..3 from `dumpsys input` (or `dumpsys window displays`) output. */
    fun parseRotation(dumpsys: String): Int? {
        for (pattern in orientationPatterns) {
            val value = pattern.find(dumpsys)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            return when (value) {
                0, 1, 2, 3 -> value
                90 -> 1
                180 -> 2
                270 -> 3
                else -> null
            }
        }
        return null
    }

    /** Video size for the given display size: fits into [maxSize] (0 = native), multiples of 8 as encoders prefer. */
    fun videoSize(display: DisplaySize, maxSize: Int): DisplaySize {
        val longest = maxOf(display.width, display.height)
        val scale = if (maxSize in 1 until longest) maxSize.toDouble() / longest else 1.0
        fun round8(v: Double) = maxOf(8, (Math.round(v / 8.0) * 8).toInt())
        return DisplaySize(round8(display.width * scale), round8(display.height * scale))
    }
}
