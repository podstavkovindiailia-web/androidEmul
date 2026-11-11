package io.androidemul.stream

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArraySet

data class SessionInfo(val serial: String, val deviceName: String, val codec: String, val width: Int, val height: Int)

/** Receives the video stream of a session. Callbacks are invoked from the session threads and must never block. */
interface StreamViewer {
    fun onStarted(info: SessionInfo)
    fun onPacket(packet: VideoPacket)
    fun onClosed(reason: String)
}

/**
 * A streaming session with one device, shared by any number of viewers.
 * Subclasses produce H.264 packets ([broadcast]) and turn [ClientMessage]s into device input ([handle]).
 */
abstract class DeviceSession(
    val serial: String,
    private val onTerminated: (DeviceSession) -> Unit,
) {
    enum class State { STARTING, RUNNING, CLOSED }

    protected val log: Logger = LoggerFactory.getLogger("${javaClass.simpleName}[$serial]")
    private val lock = Any()
    private val viewers = CopyOnWriteArraySet<StreamViewer>()

    @Volatile var state = State.STARTING
        private set
    @Volatile var info: SessionInfo? = null
        private set
    @Volatile protected var lastConfig: VideoPacket? = null

    val viewerCount: Int get() = viewers.size

    /** Blocking; called on a dedicated thread. Must call [markRunning] once the stream is ready. */
    protected abstract fun doStart()

    /** Applies an input event / command from a viewer. */
    abstract fun handle(message: ClientMessage)

    /** Asks the encoder for a fresh config packet + key frame (new viewer, viewer lost sync). Implementations rate limit. */
    abstract fun requestKeyFrame()

    /** Releases processes/sockets. Called exactly once from [close]. */
    protected abstract fun release()

    /** Extra diagnostics appended to a start failure (e.g. last device log lines). */
    protected open fun failureDetails(): String = ""

    fun start() {
        try {
            doStart()
        } catch (e: Exception) {
            val details = failureDetails()
            log.error("Failed to start: {}{}", e.message, if (details.isNotBlank()) "\n$details" else "")
            close("Failed to start: ${e.message}" + if (details.isNotBlank()) "\n$details" else "")
        }
    }

    fun addViewer(viewer: StreamViewer) {
        synchronized(lock) {
            when (state) {
                State.CLOSED -> viewer.onClosed("Session closed")
                State.STARTING -> viewers.add(viewer)
                State.RUNNING -> {
                    viewers.add(viewer)
                    viewer.onStarted(info!!)
                    // The newcomer joined mid-stream: give it the codec config and ask for a fresh key frame.
                    lastConfig?.let(viewer::onPacket)
                    requestKeyFrame()
                }
            }
        }
    }

    /** @return true if no viewers remain. */
    fun removeViewer(viewer: StreamViewer): Boolean {
        viewers.remove(viewer)
        return viewers.isEmpty()
    }

    /** @return false if the session was closed in the meantime. */
    protected fun markRunning(sessionInfo: SessionInfo): Boolean {
        synchronized(lock) {
            if (state == State.CLOSED) return false
            info = sessionInfo
            state = State.RUNNING
            viewers.forEach { it.onStarted(sessionInfo) }
        }
        return true
    }

    protected fun broadcast(packet: VideoPacket) {
        if (packet.config) lastConfig = packet
        for (viewer in viewers) viewer.onPacket(packet)
    }

    fun close(reason: String) {
        val toNotify: List<StreamViewer>
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            toNotify = viewers.toList()
            viewers.clear()
        }
        log.info("Closing session: {}", reason)
        runCatching { release() }.onFailure { log.warn("Release failed: {}", it.message) }
        toNotify.forEach { runCatching { it.onClosed(reason) } }
        onTerminated(this)
    }
}
