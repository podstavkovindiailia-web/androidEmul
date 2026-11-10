package io.androidemul.ws

import com.fasterxml.jackson.databind.ObjectMapper
import io.androidemul.config.EmulatorProperties
import io.androidemul.scrcpy.ScrcpySession
import io.androidemul.scrcpy.ScrcpySessionManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * WebSocket endpoint /ws/stream/{serial}.
 *  - server -> client: binary video packets ([flags:1][pts:8][H.264 Annex B]) and JSON status messages;
 *  - client -> server: JSON input events (touch, scroll, keys, text, commands).
 */
@Component
class StreamWebSocketHandler(
    private val manager: ScrcpySessionManager,
    private val mapper: ObjectMapper,
    private val props: EmulatorProperties,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Binding(val session: ScrcpySession, val viewer: WebSocketViewer)

    override fun afterConnectionEstablished(ws: WebSocketSession) {
        val serial = URLDecoder.decode(ws.uri!!.path.substringAfterLast('/'), StandardCharsets.UTF_8)
        log.info("Viewer {} connected to {}", ws.id, serial)
        lateinit var session: ScrcpySession
        val viewer = WebSocketViewer(ws, mapper, props.stream.viewerQueueSize) { session.requestKeyFrame() }
        viewer.sendJson(mapOf("type" to "starting", "serial" to serial))
        try {
            session = manager.attach(serial, viewer)
        } catch (e: IllegalArgumentException) {
            viewer.onClosed(e.message ?: "Invalid request")
            return
        }
        ws.attributes[BINDING] = Binding(session, viewer)
    }

    override fun handleTextMessage(ws: WebSocketSession, message: TextMessage) {
        val binding = ws.attributes[BINDING] as? Binding ?: return
        val msg = try {
            mapper.readValue(message.payload, ClientMessage::class.java)
        } catch (e: Exception) {
            log.debug("Bad message from {}: {}", ws.id, e.message)
            return
        }
        if (msg is ClientMessage.Ping) {
            binding.viewer.sendJson(mapOf("type" to "pong", "t" to msg.t))
            return
        }
        msg.toControlMessage()?.let(binding.session::send)
    }

    override fun afterConnectionClosed(ws: WebSocketSession, status: CloseStatus) {
        val binding = ws.attributes.remove(BINDING) as? Binding ?: return
        log.info("Viewer {} disconnected ({})", ws.id, status)
        binding.viewer.stop()
        manager.detach(binding.session, binding.viewer)
    }

    override fun handleTransportError(ws: WebSocketSession, exception: Throwable) {
        log.debug("Transport error {}: {}", ws.id, exception.message)
    }

    private companion object {
        const val BINDING = "binding"
    }
}
