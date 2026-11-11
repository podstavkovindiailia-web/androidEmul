package io.androidemul.scrcpy

import io.androidemul.stream.ClientMessage

/** Maps browser input to scrcpy control messages; null = invalid / not supported. */
fun ClientMessage.toControlMessage(): ControlMessage? = when (this) {
    is ClientMessage.Touch -> {
        if (action !in 0..2 || pointerId < 0 || width !in 1..0xffff || height !in 1..0xffff) null
        else ControlMessage.InjectTouch(
            action = action,
            pointerId = pointerId,
            x = x.coerceIn(0, width - 1),
            y = y.coerceIn(0, height - 1),
            screenWidth = width,
            screenHeight = height,
            pressure = if (action == ACTION_UP) 0f else pressure,
        )
    }
    is ClientMessage.Scroll ->
        if (width !in 1..0xffff || height !in 1..0xffff) null
        else ControlMessage.InjectScroll(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1), width, height, hScroll, vScroll)
    is ClientMessage.Key -> if (action in 0..1 && keycode in 0..1000) ControlMessage.InjectKeycode(action, keycode, repeat, metaState) else null
    is ClientMessage.Text -> if (text.isNotEmpty()) ControlMessage.InjectText(text) else null
    is ClientMessage.BackOrScreenOn -> if (action in 0..1) ControlMessage.BackOrScreenOn(action) else null
    is ClientMessage.Command -> when (name) {
        "expandNotifications" -> ControlMessage.Simple.EXPAND_NOTIFICATION_PANEL
        "expandSettings" -> ControlMessage.Simple.EXPAND_SETTINGS_PANEL
        "collapsePanels" -> ControlMessage.Simple.COLLAPSE_PANELS
        "rotate" -> ControlMessage.Simple.ROTATE_DEVICE
        "resetVideo" -> ControlMessage.Simple.RESET_VIDEO
        "screenOff" -> ControlMessage.SetDisplayPower(false)
        "screenOn" -> ControlMessage.SetDisplayPower(true)
        else -> null
    }
    is ClientMessage.Ping -> null
}

private const val ACTION_UP = 1
