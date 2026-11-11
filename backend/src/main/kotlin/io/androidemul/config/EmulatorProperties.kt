package io.androidemul.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

enum class StreamMode {
    /** Only built-in Android tools over adb: screenrecord (H.264) + sendevent/input. No extra software on the device. */
    NATIVE,
    /** scrcpy-server pushed to the device: lower latency, needs the scrcpy-server binary. */
    SCRCPY,
}

@ConfigurationProperties(prefix = "emulator")
data class EmulatorProperties(
    val mode: StreamMode = StreamMode.NATIVE,
    val adb: Adb = Adb(),
    val native: Native = Native(),
    val scrcpy: Scrcpy = Scrcpy(),
    val stream: Stream = Stream(),
) {
    data class Adb(
        /** Path to the adb binary. */
        val path: String = "adb",
        /**
         * Host where ports created by `adb forward` are reachable.
         * Equals 127.0.0.1 when the adb server runs on the same machine; when the backend runs in Docker
         * and talks to the host's adb server (ADB_SERVER_SOCKET=tcp:host.docker.internal:5037), set to host.docker.internal.
         */
        val forwardHost: String = "127.0.0.1",
        /** Addresses for `adb connect` (e.g. emulator:5555) that are (re)connected automatically. */
        val autoConnect: List<String> = emptyList(),
        val commandTimeout: Duration = Duration.ofSeconds(15),
    )

    data class Native(
        /** How often the display rotation is polled (the recording restarts with the new orientation). */
        val rotationPollInterval: Duration = Duration.ofSeconds(1),
        /** Min interval between screenrecord restarts triggered by viewers needing a key frame. */
        val keyFrameCooldown: Duration = Duration.ofMillis(1000),
        /** How long the stream must be silent before buffered data is treated as a complete frame. */
        val frameFlushDelay: Duration = Duration.ofMillis(2),
    )

    data class Scrcpy(
        /** Local path to scrcpy-server jar (see scripts/download-scrcpy-server.sh). */
        val serverPath: String = "scrcpy-server",
        /** Must match the scrcpy-server file version exactly. */
        val version: String = "3.1",
        val deviceServerPath: String = "/data/local/tmp/android-emul-scrcpy-server.jar",
        val connectAttempts: Int = 100,
        val connectRetryDelay: Duration = Duration.ofMillis(100),
    )

    data class Stream(
        /** Max of width/height of the video, 0 = native resolution. */
        val maxSize: Int = 1280,
        val videoBitRate: Int = 8_000_000,
        val maxFps: Int = 60,
        /** Extra MediaCodec options, e.g. "i-frame-interval=2" (scrcpy video_codec_options format). */
        val videoCodecOptions: String = "",
        /** Specific encoder name (e.g. OMX.google.h264.encoder), empty = device default. */
        val videoEncoder: String = "",
        val stayAwake: Boolean = true,
        val showTouches: Boolean = false,
        /** How long a device session survives after the last viewer disconnects (page reloads reuse it). */
        val idleTimeout: Duration = Duration.ofSeconds(10),
        /** Max video packets buffered per viewer before frames are dropped until the next key frame. */
        val viewerQueueSize: Int = 30,
    )
}
