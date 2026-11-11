package io.androidemul.nativeengine

import io.androidemul.adb.AdbClient
import io.androidemul.config.EmulatorProperties
import io.androidemul.stream.ClientMessage
import io.androidemul.stream.DeviceSession
import io.androidemul.stream.SessionInfo
import io.androidemul.stream.VideoPacket
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.hypot

/**
 * Default engine: uses only tools that ship with Android, driven over adb. Nothing is installed on the device.
 *
 *  - video: `adb exec-out screenrecord --output-format=h264 -` (hardware MediaCodec encoder of the device). The raw Annex B
 *    stream is cut into frames by [AnnexBParser]. screenrecord is restarted to get a key frame (new viewer, lost sync),
 *    when the display rotates, and when a segment ends (older Android versions limit a recording to 180 s).
 *  - touch: raw evdev events written to the touchscreen node with `sendevent` through a persistent `adb shell`
 *    ([TouchInjector], [ShellChannel]) - real multi-touch without starting a JVM per event.
 *  - keys, text, scroll, system commands: `input`, `cmd statusbar`, `settings` through a second persistent shell.
 */
class NativeSession(
    serial: String,
    private val adb: AdbClient,
    private val props: EmulatorProperties,
    onTerminated: (DeviceSession) -> Unit,
) : DeviceSession(serial, onTerminated) {
    private val cfg = props.native
    private val epoch = System.nanoTime()

    @Volatile private var rotation = 0
    @Volatile private var naturalSize = DisplaySize(0, 0)

    private var touchShell: ShellChannel? = null
    private var commandShell: ShellChannel? = null
    private var touchWorker: CoalescingWorker<TouchOp>? = null
    private var commandWorker: CoalescingWorker<CommandOp>? = null
    private var injector: TouchInjector? = null

    // screenrecord state
    private val recorderLock = Any()
    private val generation = AtomicInteger()
    private var recorder: Process? = null
    @Volatile private var unlimitedTimeLimit = unlimitedSupported[serial] ?: true
    @Volatile private var passSize = true
    private var consecutiveFailures = 0
    @Volatile private var lastRestart = 0L
    @Volatile private var restartScheduled = false
    @Volatile private var lastError = ""

    /** Fallback gesture tracking when no touchscreen device is accessible. */
    private val fallbackDown = ConcurrentHashMap<Long, FallbackPointer>()

    private data class FallbackPointer(val u: Double, val v: Double, val time: Long, var lastU: Double, var lastV: Double)

    val displaySize: DisplaySize get() = naturalSize.rotated(rotation)

    override fun failureDetails(): String = lastError

    override fun doStart() {
        val model = runCatching { adb.shellOutput(serial, "getprop ro.product.model").trim() }.getOrDefault("")
        naturalSize = DeviceProbe.parseDisplaySize(adb.shellOutput(serial, "wm size"))
            ?: throw IOException("Cannot read the display size (`wm size`)")
        rotation = readRotation() ?: 0

        val touch = runCatching { DeviceProbe.parseTouchDevice(adb.shellOutput(serial, "getevent -lp")) }.getOrNull()
        if (touch != null) {
            log.info(
                "Touchscreen {} '{}' x={}..{} y={}..{} slots={}",
                touch.path, touch.name, touch.x.min, touch.x.max, touch.y.min, touch.y.max, touch.maxSlot + 1,
            )
            injector = TouchInjector(touch)
            val shell = ShellChannel("touch-$serial", adb.startShell(serial, listOf("sh")), log)
            touchShell = shell
            touchWorker = CoalescingWorker("touch-$serial", log, TouchOp::merge, ::executeTouch)
        } else {
            log.warn("No accessible touchscreen found via getevent; falling back to `input tap/swipe` (no live drag)")
        }
        val cmd = ShellChannel("cmd-$serial", adb.startShell(serial, listOf("sh")), log)
        commandShell = cmd
        commandWorker = CoalescingWorker("cmd-$serial", log, CommandOp::merge) { batch ->
            val size = displaySize
            cmd.run(batch.map { CommandOp.render(it, size) }, timeoutMs = 15_000)
        }

        val video = DeviceProbe.videoSize(displaySize, props.stream.maxSize)
        log.info("Display {}x{} rotation {}, video {}x{}", naturalSize.width, naturalSize.height, rotation, video.width, video.height)
        if (!markRunning(SessionInfo(serial, model.ifBlank { serial }, "h264", video.width, video.height))) return

        startRecorder()
        Thread.ofVirtual().name("rotation-$serial").start(::watchRotation)
    }

    // ------------------------------------------------------------------ video

    private fun startRecorder() {
        synchronized(recorderLock) {
            if (state == State.CLOSED) return
            recorder?.let(::kill)
            val gen = generation.incrementAndGet()
            val video = DeviceProbe.videoSize(displaySize, props.stream.maxSize)
            val command = mutableListOf("screenrecord", "--output-format=h264", "--bit-rate", props.stream.videoBitRate.toString())
            if (passSize) command += listOf("--size", "${video.width}x${video.height}")
            command += listOf("--time-limit", if (unlimitedTimeLimit) "0" else "180", "-")
            log.debug("Starting {}", command.joinToString(" "))
            val proc = adb.startExecOut(serial, command)
            recorder = proc
            lastRestart = System.nanoTime()
            // platform thread: the reader is on the latency-critical path
            Thread.ofPlatform().name("screenrecord-$serial-$gen").daemon(true).start { readRecorder(proc, gen) }
        }
    }

    private fun readRecorder(proc: Process, gen: Int) {
        val stderr = StringBuilder()
        Thread.ofVirtual().start {
            runCatching { proc.errorStream.bufferedReader().forEachLine { if (stderr.length < 2000) stderr.appendLine(it) } }
        }
        val parser = AnnexBParser { config, key, data ->
            if (gen == generation.get()) {
                val pts = if (config) 0 else (System.nanoTime() - epoch) / 1000
                broadcast(VideoPacket(config, key, pts, data))
            }
        }
        val flushDelayNanos = cfg.frameFlushDelay.toNanos()
        val input = proc.inputStream
        val buffer = ByteArray(1 shl 16)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                parser.feed(buffer, 0, n)
                if (input.available() == 0) {
                    // screenrecord writes a whole frame at once: a short silence means the frame is complete
                    val deadline = System.nanoTime() + flushDelayNanos
                    while (input.available() == 0 && System.nanoTime() < deadline) LockSupport.parkNanos(250_000)
                    if (input.available() == 0) parser.flush()
                }
            }
        } catch (_: IOException) {
        }
        parser.flush()
        runCatching { proc.waitFor(1, TimeUnit.SECONDS) }

        if (gen != generation.get() || state == State.CLOSED) return // replaced on purpose or closed
        onRecorderEnded(parser, (parser.junk.toString() + stderr).trim())
    }

    private fun onRecorderEnded(parser: AnnexBParser, output: String) {
        if (parser.nalCount > 0) {
            consecutiveFailures = 0
            log.debug("screenrecord segment ended, continuing")
        } else {
            consecutiveFailures++
            lastError = output
            log.warn("screenrecord produced no video{}", if (output.isNotBlank()) ": $output" else "")
            when {
                // Android < 14 rejects --time-limit 0: switch to 180 s segments
                unlimitedTimeLimit -> {
                    unlimitedTimeLimit = false
                    unlimitedSupported[serial] = false
                }
                // the encoder may reject the requested size: let screenrecord use the native resolution
                passSize -> passSize = false
                consecutiveFailures >= 4 -> {
                    close("screenrecord failed" + if (output.isNotBlank()) ":\n$output" else "")
                    return
                }
            }
            Thread.sleep(300)
        }
        startRecorder()
    }

    /** Restarting screenrecord is the only way to get a new key frame. Rate limited; requests are coalesced. */
    override fun requestKeyFrame() {
        if (state != State.RUNNING || restartScheduled) return
        restartScheduled = true
        val wait = cfg.keyFrameCooldown.toNanos() - (System.nanoTime() - lastRestart)
        // never block the caller (WebSocket / session threads): restart asynchronously
        Thread.ofVirtual().start {
            if (wait > 0) LockSupport.parkNanos(wait)
            restartScheduled = false
            startRecorder()
        }
    }

    private fun watchRotation() {
        while (state == State.RUNNING) {
            Thread.sleep(cfg.rotationPollInterval.toMillis())
            if (state != State.RUNNING) return
            val r = runCatching { readRotation() }.getOrNull() ?: continue
            if (r != rotation) {
                log.info("Display rotation {} -> {}", rotation, r)
                rotation = r
                startRecorder()
            }
        }
    }

    private fun readRotation(): Int? {
        val input = adb.shellOutput(serial, "dumpsys input | grep -E -m 4 'SurfaceOrientation|orientation='")
        return DeviceProbe.parseRotation(input)
            ?: DeviceProbe.parseRotation(adb.shellOutput(serial, "dumpsys window displays | grep -E -m 4 'mRotation=|mCurrentRotation='"))
    }

    // ------------------------------------------------------------------ input

    override fun handle(message: ClientMessage) {
        when (message) {
            is ClientMessage.Touch -> handleTouch(message)
            is ClientMessage.Scroll -> {
                if (message.width <= 0 || message.height <= 0) return
                val size = displaySize
                val x = (message.x.toDouble() / message.width * size.width).toInt()
                val y = (message.y.toDouble() / message.height * size.height).toInt()
                val step = size.height * SCROLL_STEP
                command(CommandOp.Scroll(x, y, (message.hScroll * step).toInt(), (message.vScroll * step).toInt()))
            }
            is ClientMessage.Key -> {
                if (message.action != ACTION_DOWN || message.keycode !in 0..1000) return
                if (message.metaState and META_CTRL_ON != 0) {
                    command(CommandOp.Raw("input keycombination $KEYCODE_CTRL_LEFT ${message.keycode}"))
                } else {
                    command(CommandOp.Keys(listOf(message.keycode)))
                }
            }
            is ClientMessage.Text -> if (message.text.isNotEmpty()) command(CommandOp.Text(message.text))
            is ClientMessage.BackOrScreenOn -> if (message.action == ACTION_DOWN) command(CommandOp.Keys(listOf(KEYCODE_BACK)))
            is ClientMessage.Command -> handleCommand(message.name)
            is ClientMessage.Ping -> Unit
        }
    }

    private fun handleCommand(name: String) {
        when (name) {
            "expandNotifications" -> command(CommandOp.Raw("cmd statusbar expand-notifications"))
            "expandSettings" -> command(CommandOp.Raw("cmd statusbar expand-settings"))
            "collapsePanels" -> command(CommandOp.Raw("cmd statusbar collapse"))
            "rotate" -> {
                val next = (rotation + 1) % 4
                command(CommandOp.Raw("settings put system accelerometer_rotation 0; settings put system user_rotation $next"))
            }
            "screenOff" -> command(CommandOp.Keys(listOf(KEYCODE_SLEEP)))
            "screenOn" -> command(CommandOp.Keys(listOf(KEYCODE_WAKEUP)))
            "resetVideo" -> requestKeyFrame()
        }
    }

    private fun handleTouch(t: ClientMessage.Touch) {
        if (t.width <= 1 || t.height <= 1 || t.pointerId < 0) return
        val u = t.x.toDouble() / (t.width - 1)
        val v = t.y.toDouble() / (t.height - 1)
        val worker = touchWorker
        if (worker != null) {
            val slot = t.pointerId.toInt()
            when (t.action) {
                ACTION_DOWN -> worker.submit(TouchOp.Down(slot, u, v))
                ACTION_MOVE -> worker.submit(TouchOp.Move(slot, u, v))
                ACTION_UP -> worker.submit(TouchOp.Up(slot))
            }
        } else {
            fallbackTouch(t.action, t.pointerId, u, v)
        }
    }

    private fun executeTouch(batch: List<TouchOp>) {
        val inj = injector ?: return
        val r = rotation
        val lines = batch.flatMap { op ->
            when (op) {
                is TouchOp.Down -> inj.down(op.slot, op.u, op.v, r)
                is TouchOp.Move -> inj.move(op.slot, op.u, op.v, r)
                is TouchOp.Up -> inj.up(op.slot)
            }
        }
        touchShell?.run(lines)
    }

    /** Without access to the touchscreen node: replay the gesture with `input tap` / `input swipe` when the finger lifts. */
    private fun fallbackTouch(action: Int, pointerId: Long, u: Double, v: Double) {
        if (pointerId != 0L) return
        val now = System.currentTimeMillis()
        when (action) {
            ACTION_DOWN -> fallbackDown[pointerId] = FallbackPointer(u, v, now, u, v)
            ACTION_MOVE -> fallbackDown[pointerId]?.let { it.lastU = u; it.lastV = v }
            ACTION_UP -> {
                val p = fallbackDown.remove(pointerId) ?: return
                val size = displaySize
                val x1 = (p.u * size.width).toInt()
                val y1 = (p.v * size.height).toInt()
                val x2 = (p.lastU * size.width).toInt()
                val y2 = (p.lastV * size.height).toInt()
                val duration = (now - p.time).toInt().coerceIn(50, 3000)
                val moved = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) > size.width * 0.02
                command(
                    if (!moved && duration < 500) CommandOp.Raw("input tap $x1 $y1")
                    else CommandOp.Swipe(x1, y1, x2, y2, duration),
                )
            }
        }
    }

    private fun command(op: CommandOp) {
        commandWorker?.submit(op)
    }

    // ------------------------------------------------------------------ cleanup

    override fun release() {
        generation.incrementAndGet()
        synchronized(recorderLock) { recorder?.let(::kill) }
        touchWorker?.stop()
        commandWorker?.stop()
        injector?.let { inj -> runCatching { touchShell?.run(inj.releaseAll(), timeoutMs = 1000) } }
        touchShell?.close()
        commandShell?.close()
    }

    private fun kill(p: Process) {
        p.destroy()
        if (!runCatching { p.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) p.destroyForcibly()
    }

    private companion object {
        /** Remembered per device: whether screenrecord accepts `--time-limit 0` (Android 14+). */
        val unlimitedSupported = ConcurrentHashMap<String, Boolean>()

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val KEYCODE_BACK = 4
        const val KEYCODE_CTRL_LEFT = 113
        const val KEYCODE_SLEEP = 223
        const val KEYCODE_WAKEUP = 224
        const val META_CTRL_ON = 0x1000
        /** Share of the screen height scrolled per wheel notch. */
        const val SCROLL_STEP = 0.15
    }
}
