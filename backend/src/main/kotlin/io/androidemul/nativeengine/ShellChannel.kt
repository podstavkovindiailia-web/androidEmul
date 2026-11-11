package io.androidemul.nativeengine

import io.androidemul.adb.AdbClient
import org.slf4j.Logger
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ShellResult(val ok: Boolean, val output: List<String>) {
    val text: String get() = output.joinToString("\n")
}

/** Runs batches of shell commands on the device. */
interface CommandShell {
    /** Runs the commands (one per line) and waits until they finished; output includes stderr. */
    fun run(commands: List<String>, timeoutMs: Long = 5000): ShellResult
    fun close()
}

/**
 * A persistent `adb shell sh` process. Commands are written to its stdin, so running a command costs no adb/process
 * start-up on the host. [run] blocks until the batch has been executed on the device, which gives natural back-pressure:
 * while the device is busy, new input is queued (and coalesced) instead of piling up in a pipe.
 */
class ShellChannel(private val name: String, process: Process, private val log: Logger) : CommandShell {
    private val proc = process
    private val writer = process.outputStream.bufferedWriter()
    private val lines = LinkedBlockingQueue<String>()
    private val counter = AtomicLong()

    init {
        Thread.ofVirtual().name("shell-$name").start {
            try {
                process.inputStream.bufferedReader().forEachLine { lines.add(it) }
            } catch (_: IOException) {
            }
            lines.add(EOF)
        }
    }

    val isAlive: Boolean get() = proc.isAlive

    @Synchronized
    override fun run(commands: List<String>, timeoutMs: Long): ShellResult {
        if (commands.isEmpty()) return ShellResult(true, emptyList())
        val id = MARKER + counter.incrementAndGet()
        try {
            // a group, so that errors of every command (stderr) come back in order on stdout
            writer.write("{\n")
            for (c in commands) {
                writer.write(c)
                writer.write("\n")
            }
            writer.write("} 2>&1\necho $id\n")
            writer.flush()
        } catch (e: IOException) {
            return ShellResult(false, listOf("write failed: ${e.message}"))
        }
        val output = mutableListOf<String>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                log.warn("[{}] no answer from the device shell within {} ms", name, timeoutMs)
                return ShellResult(false, output + "timeout")
            }
            val line = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            when {
                line == id -> return ShellResult(true, output)
                line === EOF -> return ShellResult(false, output + "shell exited")
                line.startsWith(MARKER) -> Unit // answer to an earlier, timed out batch
                line.isNotBlank() -> output += line
            }
        }
    }

    override fun close() {
        runCatching { writer.write("exit\n"); writer.flush() }
        proc.destroy()
        if (!runCatching { proc.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) proc.destroyForcibly()
    }

    private companion object {
        const val MARKER = "__androidemul_done_"
        val EOF = String(charArrayOf('\u0000'))
    }
}

/** Fallback when stdin of `adb shell` cannot be used: one `adb shell` invocation per batch (slower, but always works). */
class OneShotShell(private val adb: AdbClient, private val serial: String) : CommandShell {
    override fun run(commands: List<String>, timeoutMs: Long): ShellResult {
        if (commands.isEmpty()) return ShellResult(true, emptyList())
        return try {
            val out = adb.shellOutput(serial, "{ " + commands.joinToString("; ") + "; } 2>&1; true")
            ShellResult(true, out.lines().filter { it.isNotBlank() })
        } catch (e: Exception) {
            ShellResult(false, listOf(e.message ?: e.toString()))
        }
    }

    override fun close() {}
}
