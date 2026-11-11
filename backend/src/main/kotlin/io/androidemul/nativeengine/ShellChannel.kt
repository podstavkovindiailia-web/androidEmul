package io.androidemul.nativeengine

import org.slf4j.Logger
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A persistent `adb shell sh` process. Commands are written to its stdin, so running a command costs no adb/process
 * start-up on the host. [run] blocks until the batch has been executed on the device, which gives natural back-pressure:
 * while the device is busy, new input is queued (and coalesced) instead of piling up in a pipe.
 */
class ShellChannel(private val name: String, process: Process, private val log: Logger) {
    private val proc = process
    private val writer = process.outputStream.bufferedWriter()
    private val done = LinkedBlockingQueue<String>()
    private val counter = AtomicLong()

    init {
        Thread.ofVirtual().name("shell-$name").start {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith(MARKER)) done.add(line) else if (line.isNotBlank()) log.debug("[{}] {}", name, line)
                }
            } catch (_: IOException) {
            }
        }
    }

    val isAlive: Boolean get() = proc.isAlive

    /** Runs the commands (one per line) and waits until they finished. */
    fun run(commands: List<String>, timeoutMs: Long = 5000): Boolean {
        if (commands.isEmpty()) return true
        val id = MARKER + counter.incrementAndGet()
        try {
            synchronized(writer) {
                for (c in commands) {
                    writer.write(c)
                    writer.write("\n")
                }
                writer.write("echo $id\n")
                writer.flush()
            }
        } catch (e: IOException) {
            log.warn("[{}] write failed: {}", name, e.message)
            return false
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                log.warn("[{}] timeout waiting for device", name)
                return false
            }
            val marker = done.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (marker == id) return true
        }
    }

    fun close() {
        runCatching { writer.write("exit\n"); writer.flush() }
        proc.destroy()
        if (!runCatching { proc.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) proc.destroyForcibly()
    }

    private companion object {
        const val MARKER = "__androidemul_done_"
    }
}
