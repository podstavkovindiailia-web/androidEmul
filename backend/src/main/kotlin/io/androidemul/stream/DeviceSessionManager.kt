package io.androidemul.stream

import io.androidemul.adb.AdbClient
import io.androidemul.config.EmulatorProperties
import io.androidemul.config.StreamMode
import io.androidemul.nativeengine.NativeSession
import io.androidemul.scrcpy.ScrcpySession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Keeps at most one session per device and shares it between viewers. */
@Component
class DeviceSessionManager(
    private val adb: AdbClient,
    private val props: EmulatorProperties,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, DeviceSession>()
    private val pendingStops = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "session-idle").apply { isDaemon = true } }

    init {
        log.info("Streaming engine: {}", props.mode)
    }

    @Synchronized
    fun attach(serial: String, viewer: StreamViewer): DeviceSession {
        require(SERIAL_PATTERN.matches(serial)) { "Invalid device serial" }
        pendingStops.remove(serial)?.cancel(false)
        val session = sessions[serial]?.takeIf { it.state != DeviceSession.State.CLOSED }
            ?: create(serial).also { created ->
                sessions[serial] = created
                Thread.ofVirtual().name("session-start-$serial").start { created.start() }
            }
        session.addViewer(viewer)
        return session
    }

    @Synchronized
    fun detach(session: DeviceSession, viewer: StreamViewer) {
        if (!session.removeViewer(viewer) || session.state == DeviceSession.State.CLOSED) return
        val serial = session.serial
        log.debug("No viewers left for {}, stopping in {}", serial, props.stream.idleTimeout)
        pendingStops[serial] = scheduler.schedule({
            synchronized(this) {
                pendingStops.remove(serial)
                if (session.viewerCount == 0) session.close("No viewers")
            }
        }, props.stream.idleTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun activeSessions(): Map<String, DeviceSession.State> = sessions.mapValues { it.value.state }

    private fun create(serial: String): DeviceSession = when (props.mode) {
        StreamMode.NATIVE -> NativeSession(serial, adb, props, ::onTerminated)
        StreamMode.SCRCPY -> ScrcpySession(serial, adb, props, ::onTerminated)
    }

    private fun onTerminated(session: DeviceSession) {
        sessions.remove(session.serial, session)
    }

    override fun destroy() {
        scheduler.shutdownNow()
        sessions.values.toList().forEach { it.close("Server shutdown") }
    }

    companion object {
        private val SERIAL_PATTERN = Regex("^[A-Za-z0-9._:\\-\\[\\]]{1,128}$")
    }
}
