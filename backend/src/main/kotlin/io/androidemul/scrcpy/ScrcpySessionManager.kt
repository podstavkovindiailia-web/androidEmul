package io.androidemul.scrcpy

import io.androidemul.adb.AdbClient
import io.androidemul.config.EmulatorProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Keeps at most one scrcpy session per device and shares it between viewers. */
@Component
class ScrcpySessionManager(
    private val adb: AdbClient,
    private val props: EmulatorProperties,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, ScrcpySession>()
    private val pendingStops = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "scrcpy-idle").apply { isDaemon = true } }

    @Synchronized
    fun attach(serial: String, viewer: StreamViewer): ScrcpySession {
        require(SERIAL_PATTERN.matches(serial)) { "Invalid device serial" }
        pendingStops.remove(serial)?.cancel(false)
        val session = sessions[serial]?.takeIf { it.state != ScrcpySession.State.CLOSED }
            ?: ScrcpySession(serial, adb, props, ::onTerminated).also { created ->
                sessions[serial] = created
                Thread.ofVirtual().name("scrcpy-start-$serial").start { created.start() }
            }
        session.addViewer(viewer)
        return session
    }

    @Synchronized
    fun detach(session: ScrcpySession, viewer: StreamViewer) {
        if (!session.removeViewer(viewer) || session.state == ScrcpySession.State.CLOSED) return
        val serial = session.serial
        log.debug("No viewers left for {}, stopping in {}", serial, props.stream.idleTimeout)
        pendingStops[serial] = scheduler.schedule({
            synchronized(this) {
                pendingStops.remove(serial)
                if (session.viewerCount == 0) session.close("No viewers")
            }
        }, props.stream.idleTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun activeSessions(): Map<String, ScrcpySession.State> = sessions.mapValues { it.value.state }

    private fun onTerminated(session: ScrcpySession) {
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
