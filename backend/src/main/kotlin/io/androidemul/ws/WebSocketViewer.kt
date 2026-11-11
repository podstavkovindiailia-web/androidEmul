package io.androidemul.ws

import com.fasterxml.jackson.databind.ObjectMapper
import io.androidemul.stream.SessionInfo
import io.androidemul.stream.StreamViewer
import io.androidemul.stream.VideoPacket
import org.slf4j.LoggerFactory
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Bridges a scrcpy session to one browser.
 *
 * Every viewer has its own outgoing queue and sender thread so a slow browser never stalls the device stream or other viewers.
 * If the queue grows beyond [maxQueuedPackets] (network cannot keep up), all queued video is dropped and the viewer skips
 * frames until the next key frame - latency is always preferred over completeness.
 */
class WebSocketViewer(
    private val ws: WebSocketSession,
    private val mapper: ObjectMapper,
    private val maxQueuedPackets: Int,
    private val requestKeyFrame: () -> Unit,
) : StreamViewer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = LinkedBlockingQueue<WebSocketMessage<*>>()

    @Volatile private var awaitingKeyFrame = true
    @Volatile private var needConfig = false
    @Volatile private var lastConfig: VideoPacket? = null
    @Volatile private var running = true

    private val sender = Thread.ofVirtual().name("ws-sender-${ws.id}").start(::sendLoop)

    override fun onStarted(info: SessionInfo) {
        sendJson(mapOf("type" to "started", "deviceName" to info.deviceName, "codec" to info.codec, "width" to info.width, "height" to info.height, "input" to info.input))
    }

    override fun onPacket(packet: VideoPacket) {
        if (!running) return
        if (packet.config) {
            lastConfig = packet
            needConfig = false
            enqueue(packet)
            return
        }
        if (awaitingKeyFrame) {
            if (!packet.keyFrame) return
            awaitingKeyFrame = false
            if (needConfig) {
                lastConfig?.let(::enqueue)
                needConfig = false
            }
        }
        if (queue.size >= maxQueuedPackets) {
            // Client is too slow: drop everything buffered and resync on the next key frame.
            queue.removeIf { it is BinaryMessage }
            awaitingKeyFrame = true
            needConfig = true
            log.debug("Viewer {} overflow, dropping frames until next key frame", ws.id)
            requestKeyFrame()
            return
        }
        enqueue(packet)
    }

    override fun onClosed(reason: String) {
        sendJson(mapOf("type" to "closed", "reason" to reason))
        queue.add(CLOSE_MARKER)
    }

    fun sendJson(payload: Any) {
        if (running) queue.add(TextMessage(mapper.writeValueAsString(payload)))
    }

    fun stop() {
        running = false
        sender.interrupt()
    }

    private fun enqueue(packet: VideoPacket) {
        queue.add(BinaryMessage(ByteBuffer.wrap(packet.toWireFormat())))
    }

    private fun sendLoop() {
        try {
            while (running) {
                val message = queue.take()
                if (message === CLOSE_MARKER) {
                    ws.close(CloseStatus.NORMAL)
                    break
                }
                if (!ws.isOpen) break
                ws.sendMessage(message)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            log.debug("Viewer {} send failed: {}", ws.id, e.message)
            runCatching { ws.close(CloseStatus.SERVER_ERROR) }
        } finally {
            running = false
        }
    }

    private companion object {
        val CLOSE_MARKER = TextMessage("__close__")
    }
}
