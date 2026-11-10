package io.androidemul.ws

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.androidemul.scrcpy.ControlMessage

/** JSON messages browser -> server. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(ClientMessage.Touch::class, name = "touch"),
    JsonSubTypes.Type(ClientMessage.Scroll::class, name = "scroll"),
    JsonSubTypes.Type(ClientMessage.Key::class, name = "key"),
    JsonSubTypes.Type(ClientMessage.Text::class, name = "text"),
    JsonSubTypes.Type(ClientMessage.BackOrScreenOn::class, name = "backOrScreenOn"),
    JsonSubTypes.Type(ClientMessage.Command::class, name = "command"),
    JsonSubTypes.Type(ClientMessage.Ping::class, name = "ping"),
)
sealed interface ClientMessage {
    /** action: 0 = down, 1 = up, 2 = move. Coordinates in pixels of the decoded video frame (width x height). */
    data class Touch(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val pressure: Float = 1f,
    ) : ClientMessage

    data class Scroll(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val hScroll: Float,
        val vScroll: Float,
    ) : ClientMessage

    /** action: 0 = down, 1 = up. keycode = Android KeyEvent.KEYCODE_*. */
    data class Key(val action: Int, val keycode: Int, val repeat: Int = 0, val metaState: Int = 0) : ClientMessage

    data class Text(val text: String) : ClientMessage

    data class BackOrScreenOn(val action: Int) : ClientMessage

    /** name: expandNotifications | expandSettings | collapsePanels | rotate | resetVideo | screenOff | screenOn */
    data class Command(val name: String) : ClientMessage

    data class Ping(val t: Double) : ClientMessage
}

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
