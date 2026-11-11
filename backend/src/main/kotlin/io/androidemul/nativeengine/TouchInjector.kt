package io.androidemul.nativeengine

/**
 * Builds `sendevent` commands that write raw evdev events into the touchscreen device node,
 * exactly what the kernel driver would emit for a real finger (multi-touch protocol B).
 * Not thread safe: used from the single input worker.
 */
class TouchInjector(private val device: TouchDevice) {
    private val activeSlots = mutableSetOf<Int>()
    private var nextTrackingId = 1

    val maxPointers: Int get() = if (device.multiTouch) device.maxSlot + 1 else 1

    fun down(slot: Int, u: Double, v: Double, rotation: Int): List<String> {
        if (slot !in 0 until maxPointers) return emptyList()
        val (x, y) = toRaw(u, v, rotation)
        val out = mutableListOf<String>()
        if (device.multiTouch) {
            if (slot in activeSlots) out += up(slot, sync = false)
            out += abs(ABS_MT_SLOT, slot)
            out += abs(ABS_MT_TRACKING_ID, nextTrackingId)
            nextTrackingId = if (nextTrackingId >= 0xfffe) 1 else nextTrackingId + 1
            out += abs(ABS_MT_POSITION_X, x)
            out += abs(ABS_MT_POSITION_Y, y)
            device.pressure?.let { out += abs(ABS_MT_PRESSURE, mid(it)) }
            device.touchMajor?.let { out += abs(ABS_MT_TOUCH_MAJOR, small(it)) }
            if (activeSlots.isEmpty() && device.hasBtnTouch) out += key(BTN_TOUCH, 1)
        } else {
            out += abs(ABS_X, x)
            out += abs(ABS_Y, y)
            device.pressure?.let { out += abs(ABS_PRESSURE, mid(it)) }
            out += key(BTN_TOUCH, 1)
        }
        activeSlots += slot
        out += syn()
        return out
    }

    fun move(slot: Int, u: Double, v: Double, rotation: Int): List<String> {
        if (slot !in activeSlots) return emptyList()
        val (x, y) = toRaw(u, v, rotation)
        val out = mutableListOf<String>()
        if (device.multiTouch) {
            out += abs(ABS_MT_SLOT, slot)
            out += abs(ABS_MT_POSITION_X, x)
            out += abs(ABS_MT_POSITION_Y, y)
        } else {
            out += abs(ABS_X, x)
            out += abs(ABS_Y, y)
        }
        out += syn()
        return out
    }

    fun up(slot: Int, sync: Boolean = true): List<String> {
        if (slot !in activeSlots) return emptyList()
        activeSlots -= slot
        val out = mutableListOf<String>()
        if (device.multiTouch) {
            out += abs(ABS_MT_SLOT, slot)
            out += abs(ABS_MT_TRACKING_ID, -1)
            if (activeSlots.isEmpty() && device.hasBtnTouch) out += key(BTN_TOUCH, 0)
        } else {
            device.pressure?.let { out += abs(ABS_PRESSURE, 0) }
            out += key(BTN_TOUCH, 0)
        }
        if (sync) out += syn()
        return out
    }

    fun releaseAll(): List<String> = activeSlots.toList().flatMap { up(it) }

    /** Maps normalized coordinates of the (rotated) display to the raw axes of the panel in its natural orientation. */
    fun toRaw(u: Double, v: Double, rotation: Int): Pair<Int, Int> {
        val cu = u.coerceIn(0.0, 1.0)
        val cv = v.coerceIn(0.0, 1.0)
        val (rx, ry) = when (rotation) {
            1 -> (1 - cv) to cu
            2 -> (1 - cu) to (1 - cv)
            3 -> cv to (1 - cu)
            else -> cu to cv
        }
        return scale(rx, device.x) to scale(ry, device.y)
    }

    private fun scale(f: Double, r: AxisRange) = (r.min + Math.round(f * (r.max - r.min))).toInt()
    private fun mid(r: AxisRange) = maxOf(r.min + 1, r.min + (r.max - r.min) / 2).coerceAtMost(r.max)
    private fun small(r: AxisRange) = maxOf(r.min + 1, 5).coerceAtMost(r.max)

    private fun abs(code: Int, value: Int) = "sendevent ${device.path} $EV_ABS $code $value"
    private fun key(code: Int, value: Int) = "sendevent ${device.path} $EV_KEY $code $value"
    private fun syn() = "sendevent ${device.path} $EV_SYN $SYN_REPORT 0"

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14a
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_PRESSURE = 0x18
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_TOUCH_MAJOR = 0x30
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_PRESSURE = 0x3a
    }
}
