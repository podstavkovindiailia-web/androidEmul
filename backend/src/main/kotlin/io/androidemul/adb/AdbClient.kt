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

    /** Resolved adb executable: explicit ADB_PATH, otherwise PATH, ANDROID_HOME / ANDROID_SDK_ROOT and default SDK locations. */
    val adbPath: String by lazy {
        val found = adbCandidates().firstOrNull { File(it).let { f -> f.isFile && f.canExecute() } }
        val resolved = found ?: props.adb.path
        if (found != null) log.info("Using adb: {}", found) else log.warn("adb not found, falling back to '{}'", resolved)
        resolved
    }

    fun adbCandidates(): List<String> {
        val configured = props.adb.path
        // an explicit path (anything other than the bare default "adb") is used as is
        if (configured != "adb") return listOf(configured)
        val exe = if (System.getProperty("os.name").lowercase().startsWith("windows")) "adb.exe" else "adb"
        val home = System.getProperty("user.home")
        val pathDirs = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        val sdkDirs = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "$home/Android/Sdk", // Linux (Android Studio default)
            "$home/Library/Android/sdk", // macOS
            System.getenv("LOCALAPPDATA")?.let { "$it\\Android\\Sdk" }, // Windows
            "/opt/android-sdk",
            "/usr/lib/android-sdk",
        ).map { File(it, "platform-tools").path }
        return (pathDirs + sdkDirs).map { File(it, exe).path }.distinct()
    }

    fun devices(): List<AdbDevice> {
        val output = run(listOf("devices", "-l"))
        return parseDevices(output)
    }

    fun connect(address: String): String = run(listOf("connect", address)).trim()

    /** Runs a short shell command on the device and returns its output. */
    fun shellOutput(serial: String, command: String): String = run(listOf("-s", serial, "shell", command))

    /**
     * Starts `adb exec-out <command>`: raw, binary-safe stdout (no pty, no CRLF translation).
     * stderr of the local adb client stays separate.
     */
    fun startExecOut(serial: String, command: List<String>): Process =
        ProcessBuilder(listOf(adbPath, "-s", serial, "exec-out") + command).start()

    /** Starts a long-running `adb shell` command; the caller owns the process. */
    fun startShell(serial: String, command: List<String>): Process =
        ProcessBuilder(listOf(adbPath, "-s", serial, "shell") + command)
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
        val cmd = listOf(adbPath) + args
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw AdbException(
                "Cannot start adb ('$adbPath'). Install Android SDK platform-tools and either add them to PATH, " +
                    "set ANDROID_HOME, or set ADB_PATH=/path/to/adb. Searched: ${adbCandidates().joinToString()}",
                e,
            )
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
