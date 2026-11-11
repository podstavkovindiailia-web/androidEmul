package io.androidemul.nativeengine

import org.slf4j.Logger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-threaded executor that hands everything queued so far to [execute] as one batch.
 * While a batch runs on the device, new items accumulate and may be merged ([merge]),
 * e.g. intermediate finger positions are dropped in favour of the latest one - so input never lags behind.
 */
class CoalescingWorker<T : Any>(
    name: String,
    private val log: Logger,
    private val merge: (queue: MutableList<T>, item: T) -> Boolean,
    private val execute: (batch: List<T>) -> Unit,
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayList<T>()
    @Volatile private var running = true
    private val thread = Thread.ofVirtual().name(name).start(::loop)

    fun submit(item: T) {
        lock.withLock {
            if (!merge(queue, item)) queue.add(item)
            notEmpty.signal()
        }
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        while (running) {
            val batch = try {
                lock.withLock {
                    while (queue.isEmpty()) notEmpty.await()
                    ArrayList(queue).also { queue.clear() }
                }
            } catch (_: InterruptedException) {
                return
            }
            try {
                execute(batch)
            } catch (e: Exception) {
                log.warn("Input batch failed: {}", e.message)
            }
        }
    }
}

/** Touch operations; coordinates normalized to [0, 1] in the current display orientation. */
sealed interface TouchOp {
    val slot: Int

    data class Down(override val slot: Int, val u: Double, val v: Double) : TouchOp
    data class Move(override val slot: Int, val u: Double, val v: Double) : TouchOp
    data class Up(override val slot: Int) : TouchOp

    companion object {
        /** A move replaces a queued move of the same finger (only across other moves, never across down/up). */
        fun merge(queue: MutableList<TouchOp>, item: TouchOp): Boolean {
            if (item !is Move) return false
            for (i in queue.indices.reversed()) {
                val queued = queue[i]
                if (queued !is Move) return false
                if (queued.slot == item.slot) {
                    queue[i] = item
                    return true
                }
            }
            return false
        }
    }
}

/** Slow operations executed via the `input` / `cmd` / `settings` tools. */
sealed interface CommandOp {
    data class Keys(val keycodes: List<Int>) : CommandOp
    data class Text(val text: String) : CommandOp
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : CommandOp
    data class Scroll(val x: Int, val y: Int, val dx: Int, val dy: Int) : CommandOp
    data class Raw(val command: String) : CommandOp

    companion object {
        /** Consecutive key presses / text / scroll steps are merged into a single command. */
        fun merge(queue: MutableList<CommandOp>, item: CommandOp): Boolean {
            val last = queue.lastOrNull() ?: return false
            val merged = when {
                last is Keys && item is Keys -> Keys(last.keycodes + item.keycodes)
                last is Text && item is Text && last.text.length < 200 -> Text(last.text + item.text)
                last is Scroll && item is Scroll -> Scroll(last.x, last.y, last.dx + item.dx, last.dy + item.dy)
                else -> return false
            }
            queue[queue.size - 1] = merged
            return true
        }

        fun render(op: CommandOp, display: DisplaySize): String = when (op) {
            is Keys -> "input keyevent " + op.keycodes.joinToString(" ")
            is Text -> "input text " + shellQuote(escapeInputText(op.text))
            is Swipe -> "input swipe ${op.x1} ${op.y1} ${op.x2} ${op.y2} ${op.durationMs}"
            is Scroll -> {
                // a short drag in the scroll direction; clamp to the screen
                val x2 = (op.x + op.dx).coerceIn(1, display.width - 2)
                val y2 = (op.y + op.dy).coerceIn(1, display.height - 2)
                "input swipe ${op.x} ${op.y} $x2 $y2 200"
            }
            is Raw -> op.command
        }

        /** `input text` treats "%s" as a space and cannot type non-ASCII characters. */
        fun escapeInputText(text: String): String =
            text.filter { it in ' '..'~' }.replace(" ", "%s")

        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
