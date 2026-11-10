package io.androidemul.scrcpy

import io.androidemul.adb.AdbClient
import io.androidemul.config.EmulatorProperties
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

data class SessionInfo(val serial: String, val deviceName: String, val codec: String, val width: Int, val height: Int)

/** Receives the video stream of a session. Callbacks are invoked from the session threads and must never block. */
interface StreamViewer {
    fun onStarted(info: SessionInfo)
    fun onPacket(packet: VideoPacket)
    fun onClosed(reason: String)
}

/**
 * One scrcpy-server instance running on a device, shared by any number of viewers.
 *
 * Flow: push server jar -> adb forward tcp:<port> localabstract:scrcpy_<scid> -> start server via `adb shell app_process`
 * -> connect video socket (wait for dummy byte) -> connect control socket -> read device/codec meta -> stream packets.
 */
class ScrcpySession(
    val serial: String,
    private val adb: AdbClient,
    private val props: EmulatorProperties,
    private val onTerminated: (ScrcpySession) -> Unit,
) {
    enum class State { STARTING, RUNNING, CLOSED }

    private val log = LoggerFactory.getLogger("scrcpy[$serial]")
    private val lock = Any()
    private val viewers = CopyOnWriteArraySet<StreamViewer>()
    private val serverLog = ArrayDeque<String>()

    @Volatile var state = State.STARTING
        private set
    @Volatile var info: SessionInfo? = null
        private set
    @Volatile private var lastConfig: VideoPacket? = null
    @Volatile private var lastKeyFrameRequest = 0L

    private var process: Process? = null
    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var controlOut: OutputStream? = null
    private var forwardPort = -1

    val viewerCount: Int get() = viewers.size

    fun addViewer(viewer: StreamViewer) {
        synchronized(lock) {
            when (state) {
                State.CLOSED -> {
                    viewer.onClosed("Session closed"); return
                }
                State.STARTING -> viewers.add(viewer)
                State.RUNNING -> {
                    viewers.add(viewer)
                    viewer.onStarted(info!!)
                    // The newcomer joined mid-stream: give it the codec config and ask the encoder for a fresh key frame.
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

    fun send(message: ControlMessage) {
        val out = controlOut ?: return
        val bytes = message.serialize()
        try {
            synchronized(out) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: IOException) {
            close("Control channel failed: ${e.message}")
        }
    }

    /** Asks the encoder to restart (=> config packet + key frame). Rate limited. */
    fun requestKeyFrame() {
        val now = System.nanoTime()
        if (now - lastKeyFrameRequest < TimeUnit.MILLISECONDS.toNanos(300)) return
        lastKeyFrameRequest = now
        send(ControlMessage.Simple.RESET_VIDEO)
    }

    /** Blocking; call from a dedicated thread. */
    fun start() {
        try {
            doStart()
        } catch (e: Exception) {
            val details = synchronized(serverLog) { serverLog.joinToString("\n") }
            log.error("Failed to start scrcpy session: {}\n{}", e.message, details)
            close("Failed to start: ${e.message}" + if (details.isNotBlank()) "\n$details" else "")
        }
    }

    private fun doStart() {
        val cfg = props.scrcpy
        val stream = props.stream
        val jar = File(cfg.serverPath)
        log.info("Pushing {} to {}", jar.absolutePath, cfg.deviceServerPath)
        adb.push(serial, jar, cfg.deviceServerPath)

        val scid = ThreadLocalRandom.current().nextInt(0x7fffffff)
        val socketName = "scrcpy_%08x".format(scid)
        forwardPort = ServerSocket(0).use { it.localPort }
        adb.forward(serial, forwardPort, "localabstract:$socketName")

        val args = mutableListOf(
            "CLASSPATH=${cfg.deviceServerPath}", "app_process", "/", "com.genymobile.scrcpy.Server", cfg.version,
            "scid=%08x".format(scid),
            "log_level=info",
            "tunnel_forward=true",
            "audio=false",
            "control=true",
            "video_codec=h264",
            "max_size=${stream.maxSize}",
            "video_bit_rate=${stream.videoBitRate}",
            "max_fps=${stream.maxFps}",
            "stay_awake=${stream.stayAwake}",
            "show_touches=${stream.showTouches}",
            "clipboard_autosync=false",
            "power_on=true",
        )
        if (stream.videoCodecOptions.isNotBlank()) args += "video_codec_options=${stream.videoCodecOptions}"
        if (stream.videoEncoder.isNotBlank()) args += "video_encoder=${stream.videoEncoder}"

        log.info("Starting scrcpy-server {} (scid={}, local port {})", cfg.version, "%08x".format(scid), forwardPort)
        val proc = adb.startShell(serial, args)
        process = proc
        Thread.ofVirtual().name("scrcpy-log-$serial").start { pumpServerLog(proc) }

        val video = connectVideoSocket(proc)
        videoSocket = video
        val control = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(props.adb.forwardHost, forwardPort), 5000)
        }
        controlSocket = control
        controlOut = control.getOutputStream()

        val input = DataInputStream(BufferedInputStream(video.getInputStream(), 1 shl 16))
        val nameBytes = ByteArray(DEVICE_NAME_LENGTH).also { input.readFully(it) }
        val deviceName = String(nameBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
        val codecId = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        if (codecId != CODEC_ID_H264) throw IOException("Unexpected codec id 0x%08x".format(codecId))

        val sessionInfo = SessionInfo(serial, deviceName, "h264", width, height)
        log.info("Streaming '{}' {}x{}", deviceName, width, height)

        synchronized(lock) {
            if (state == State.CLOSED) return
            info = sessionInfo
            state = State.RUNNING
            viewers.forEach { it.onStarted(sessionInfo) }
        }

        Thread.ofVirtual().name("scrcpy-ctrl-$serial").start { drainDeviceMessages(control) }
        Thread.ofPlatform().name("scrcpy-video-$serial").daemon(true).start { readVideo(input) }
    }

    /**
     * With adb forward the TCP connect always succeeds (adb accepts it) even before the server listens on the device;
     * the server sends a dummy byte once the connection is really established, so retry until it is received.
     */
    private fun connectVideoSocket(proc: Process): Socket {
        val cfg = props.scrcpy
        repeat(cfg.connectAttempts) {
            if (state == State.CLOSED) throw IOException("Session closed")
            if (!proc.isAlive) throw IOException("scrcpy-server exited with code ${proc.exitValue()}")
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(props.adb.forwardHost, forwardPort), 2000)
                if (socket.getInputStream().read() != -1) return socket
            } catch (_: IOException) {
            }
            socket.close()
            Thread.sleep(cfg.connectRetryDelay.toMillis())
        }
        throw IOException("Could not connect to scrcpy-server")
    }

    private fun readVideo(input: DataInputStream) {
        try {
            while (state == State.RUNNING) {
                val ptsAndFlags = input.readLong()
                val size = input.readInt()
                if (size <= 0 || size > MAX_PACKET_SIZE) throw IOException("Invalid packet size $size")
                val data = ByteArray(size)
                input.readFully(data)
                val packet = VideoPacket.fromScrcpyHeader(ptsAndFlags, data)
                if (packet.config) lastConfig = packet
                for (viewer in viewers) viewer.onPacket(packet)
            }
        } catch (e: EOFException) {
            close("Device stream ended")
        } catch (e: IOException) {
            close("Video stream error: ${e.message}")
        }
    }

    /** The device may send clipboard / ack / uhid messages back; we do not use them, but the socket must be drained. */
    private fun drainDeviceMessages(socket: Socket) {
        val buffer = ByteArray(8192)
        try {
            val input = socket.getInputStream()
            while (input.read(buffer) != -1) { /* discard */ }
        } catch (_: IOException) {
        }
    }

    private fun pumpServerLog(proc: Process) {
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                log.info("[server] {}", line)
                synchronized(serverLog) {
                    serverLog.addLast(line)
                    while (serverLog.size > 20) serverLog.removeFirst()
                }
            }
        } catch (_: IOException) {
        }
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        if (state != State.CLOSED) {
            val details = synchronized(serverLog) { serverLog.takeLast(5).joinToString("\n") }
            close("scrcpy-server exited (code $code)" + if (details.isNotBlank()) ":\n$details" else "")
        }
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
        runCatching { videoSocket?.close() }
        runCatching { controlSocket?.close() }
        process?.let { p ->
            p.destroy()
            if (!runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
        }
        if (forwardPort > 0) adb.removeForward(serial, forwardPort)
        toNotify.forEach { runCatching { it.onClosed(reason) } }
        onTerminated(this)
    }

    companion object {
        private const val DEVICE_NAME_LENGTH = 64
        private const val CODEC_ID_H264 = 0x68323634 // "h264"
        private const val MAX_PACKET_SIZE = 32 * 1024 * 1024
    }
}
