package io.androidemul.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "emulator")
data class EmulatorProperties(
    val adb: Adb = Adb(),
    val native: Native = Native(),
    val stream: Stream = Stream(),
) {
    data class Adb(
        /** Path to the adb binary ("adb" = auto-detect in PATH, ANDROID_HOME and default SDK folders). */
        val path: String = "adb",
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

    data class Stream(
        /** Max of width/height of the video, 0 = native resolution. */
        val maxSize: Int = 1280,
        val videoBitRate: Int = 8_000_000,
        /** How long a device session survives after the last viewer disconnects (page reloads reuse it). */
        val idleTimeout: Duration = Duration.ofSeconds(10),
        /** Max video packets buffered per viewer before frames are dropped until the next key frame. */
        val viewerQueueSize: Int = 30,
    )
}
