package com.macrostack.app.stack

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Saves frames on several threads so storage never holds up the camera. The queue is bounded:
 * when it is full the submitting thread writes the frame itself, which slows the camera down
 * instead of running out of memory.
 */
class FrameWriter(
    queueCapacity: Int,
    threads: Int = DEFAULT_THREADS,
    /** Called after each write with null on success, or what went wrong. */
    private val onDone: (error: Exception?) -> Unit,
) {
    // Run on the caller when full — and also after finish(), so a late frame's resources are still released.
    private val runOnCaller = RejectedExecutionHandler { task, _ -> task.run() }

    private val executor = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity.coerceAtLeast(1)),
        runOnCaller,
    )

    fun submit(index: Int, write: () -> Unit) {
        executor.execute {
            val error = try {
                write()
                null
            } catch (e: Exception) {
                Log.e(TAG, "Saving frame ${index + 1} failed", e)
                e
            }
            onDone(error)
        }
    }

    /** Stops accepting frames and waits (blocking) for queued ones to be written. */
    fun finish(timeoutMs: Long): Boolean {
        executor.shutdown()
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "FrameWriter"
        private const val DEFAULT_THREADS = 3
        private const val MAX_QUEUED_BYTES = 96L * 1024 * 1024

        /** Queue length that keeps roughly [MAX_QUEUED_BYTES] of JPEGs in memory at most. */
        fun capacityFor(width: Int, height: Int): Int {
            val estimatedJpegBytes = (width.toLong() * height / 2).coerceAtLeast(1)
            return (MAX_QUEUED_BYTES / estimatedJpegBytes).toInt().coerceIn(4, 48)
        }
    }
}
