package io.androidemul.stream

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** JSON messages browser -> server (engine independent). */
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
