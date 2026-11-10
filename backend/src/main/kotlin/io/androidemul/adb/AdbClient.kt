package io.androidemul.adb

import io.androidemul.config.EmulatorProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String?,
    val product: String?,
    val transportId: String?,
    val emulator: Boolean,
)

class AdbException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thin wrapper around the `adb` command-line client.
 * The adb server (local or remote via ADB_SERVER_SOCKET) does all the transport work.
 */
@Component
class AdbClient(private val props: EmulatorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun devices(): List<AdbDevice> {
        val output = run(listOf("devices", "-l"))
        return parseDevices(output)
    }

    fun push(serial: String, local: File, remote: String) {
        if (!local.isFile) throw AdbException("File not found: ${local.absolutePath}")
        run(listOf("-s", serial, "push", local.absolutePath, remote))
    }

    fun forward(serial: String, localPort: Int, remote: String) {
        run(listOf("-s", serial, "forward", "tcp:$localPort", remote))
    }

    fun removeForward(serial: String, localPort: Int) {
        try {
            run(listOf("-s", serial, "forward", "--remove", "tcp:$localPort"))
        } catch (e: AdbException) {
            log.debug("Failed to remove forward tcp:{} for {}: {}", localPort, serial, e.message)
        }
    }

    fun connect(address: String): String = run(listOf("connect", address)).trim()

    /** Starts a long-running `adb shell` command; the caller owns the process. */
    fun startShell(serial: String, command: List<String>): Process =
        ProcessBuilder(listOf(props.adb.path, "-s", serial, "shell") + command)
            .redirectErrorStream(true)
            .start()

    @Scheduled(initialDelay = 0, fixedDelay = 30_000)
    fun autoConnect() {
        for (address in props.adb.autoConnect.filter { it.isNotBlank() }) {
            try {
                log.debug("adb connect {}: {}", address, connect(address))
            } catch (e: Exception) {
                log.warn("adb connect {} failed: {}", address, e.message)
            }
        }
    }

    private fun run(args: List<String>): String {
        val cmd = listOf(props.adb.path) + args
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw AdbException("Cannot start '${props.adb.path}'. Is adb installed and in PATH?", e)
        }
        // Read output on a separate thread so that a chatty process cannot deadlock on a full pipe.
        var output = ""
        val reader = Thread.ofVirtual().start { output = process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(props.adb.commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw AdbException("Timeout: ${cmd.joinToString(" ")}")
        }
        reader.join(1000)
        if (process.exitValue() != 0) {
            throw AdbException("${cmd.joinToString(" ")} failed (${process.exitValue()}): ${output.trim()}")
        }
        return output
    }

    companion object {
        fun parseDevices(output: String): List<AdbDevice> =
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 2) return@mapNotNull null
                    val attrs = parts.drop(2).mapNotNull {
                        val idx = it.indexOf(':')
                        if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
                    }.toMap()
                    AdbDevice(
                        serial = parts[0],
                        state = parts[1],
                        model = attrs["model"],
                        product = attrs["product"],
                        transportId = attrs["transport_id"],
                        emulator = parts[0].startsWith("emulator-"),
                    )
                }
                .toList()
    }
}
