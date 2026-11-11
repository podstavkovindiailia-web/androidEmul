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
        Regex("""SurfaceOrientation:\s*(\d)"""),
        Regex("""orientation=(\d)"""),
        Regex("""mCurrentRotation=(?:ROTATION_)?(\d+)"""),
        Regex("""mRotation=(?:ROTATION_)?(\d+)"""),
    )

    /** Picks the touchscreen from `getevent -lp` output. */
    fun parseTouchDevice(getevent: String): TouchDevice? {
        val headers = deviceHeader.findAll(getevent).toList()
        val devices = headers.mapIndexed { idx, header ->
            val end = if (idx + 1 < headers.size) headers[idx + 1].range.first else getevent.length
            val block = getevent.substring(header.range.first, end)
            parseBlock(header.groupValues[1], block)
        }.filterNotNull()
        return devices.sortedWith(
            compareByDescending<Pair<TouchDevice, Int>> { it.second }
                .thenBy { it.first.path.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE },
        ).firstOrNull()?.first
    }

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
