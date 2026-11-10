package io.androidemul.scrcpy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Control messages of the scrcpy protocol (client -> device), binary-compatible with scrcpy 3.x
 * (see app/src/control_msg.c in the scrcpy repository).
 */
sealed interface ControlMessage {
    fun serialize(): ByteArray

    /** Android KeyEvent. action: 0 = down, 1 = up. */
    data class InjectKeycode(val action: Int, val keycode: Int, val repeat: Int = 0, val metaState: Int = 0) : ControlMessage {
        override fun serialize(): ByteArray = ByteBuffer.allocate(14)
            .put(TYPE_INJECT_KEYCODE)
            .put(action.toByte())
            .putInt(keycode)
            .putInt(repeat)
            .putInt(metaState)
            .array()
    }

    data class InjectText(val text: String) : ControlMessage {
        override fun serialize(): ByteArray {
            val bytes = truncateUtf8(text, INJECT_TEXT_MAX_LENGTH)
            return ByteBuffer.allocate(1 + 4 + bytes.size)
                .put(TYPE_INJECT_TEXT)
                .putInt(bytes.size)
                .put(bytes)
                .array()
        }
    }

    /**
     * Touch/mouse event. Coordinates are in video pixels, and [screenWidth]x[screenHeight] must equal the current video size,
     * otherwise the device ignores the event (this protects against stale coordinates after a rotation).
     */
    data class InjectTouch(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val pressure: Float,
        val actionButton: Int = 0,
        val buttons: Int = 0,
    ) : ControlMessage {
        override fun serialize(): ByteArray = ByteBuffer.allocate(32)
            .put(TYPE_INJECT_TOUCH_EVENT)
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(screenWidth.toShort())
            .putShort(screenHeight.toShort())
            .putShort(floatToU16FixedPoint(pressure))
            .putInt(actionButton)
            .putInt(buttons)
            .array()
    }

    /** hScroll/vScroll in [-1, 1]. */
    data class InjectScroll(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val hScroll: Float,
        val vScroll: Float,
        val buttons: Int = 0,
    ) : ControlMessage {
        override fun serialize(): ByteArray = ByteBuffer.allocate(21)
            .put(TYPE_INJECT_SCROLL_EVENT)
            .putInt(x)
            .putInt(y)
            .putShort(screenWidth.toShort())
            .putShort(screenHeight.toShort())
            .putShort(floatToI16FixedPoint(hScroll))
            .putShort(floatToI16FixedPoint(vScroll))
            .putInt(buttons)
            .array()
    }

    data class BackOrScreenOn(val action: Int) : ControlMessage {
        override fun serialize(): ByteArray = byteArrayOf(TYPE_BACK_OR_SCREEN_ON, action.toByte())
    }

    data class SetDisplayPower(val on: Boolean) : ControlMessage {
        override fun serialize(): ByteArray = byteArrayOf(TYPE_SET_DISPLAY_POWER, if (on) 1 else 0)
    }

    /** Messages without payload. */
    enum class Simple(private val type: Byte) : ControlMessage {
        EXPAND_NOTIFICATION_PANEL(TYPE_EXPAND_NOTIFICATION_PANEL),
        EXPAND_SETTINGS_PANEL(TYPE_EXPAND_SETTINGS_PANEL),
        COLLAPSE_PANELS(TYPE_COLLAPSE_PANELS),
        ROTATE_DEVICE(TYPE_ROTATE_DEVICE),
        /** Restarts capture/encoding: the device emits a fresh config packet followed by a key frame. */
        RESET_VIDEO(TYPE_RESET_VIDEO);

        override fun serialize(): ByteArray = byteArrayOf(type)
    }

    companion object {
        const val TYPE_INJECT_KEYCODE: Byte = 0
        const val TYPE_INJECT_TEXT: Byte = 1
        const val TYPE_INJECT_TOUCH_EVENT: Byte = 2
        const val TYPE_INJECT_SCROLL_EVENT: Byte = 3
        const val TYPE_BACK_OR_SCREEN_ON: Byte = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL: Byte = 5
        const val TYPE_EXPAND_SETTINGS_PANEL: Byte = 6
        const val TYPE_COLLAPSE_PANELS: Byte = 7
        const val TYPE_SET_DISPLAY_POWER: Byte = 10
        const val TYPE_ROTATE_DEVICE: Byte = 11
        const val TYPE_RESET_VIDEO: Byte = 17

        const val INJECT_TEXT_MAX_LENGTH = 300

        /** Same rounding as scrcpy's sc_float_to_u16fp: 1.0 -> 0xffff. */
        fun floatToU16FixedPoint(f: Float): Short {
            val clamped = f.coerceIn(0f, 1f)
            val u = (clamped * 65536f).toLong().coerceAtMost(0xffff)
            return u.toInt().toShort()
        }

        /** Same as scrcpy's sc_float_to_i16fp: [-1, 1] -> [-0x8000, 0x7fff]. */
        fun floatToI16FixedPoint(f: Float): Short {
            val clamped = f.coerceIn(-1f, 1f)
            val i = (clamped * 32768f).toInt().coerceIn(-0x8000, 0x7fff)
            return i.toShort()
        }

        fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            var end = maxBytes
            // do not cut in the middle of a multi-byte sequence (continuation bytes are 10xxxxxx)
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOf(end)
        }
    }
}
