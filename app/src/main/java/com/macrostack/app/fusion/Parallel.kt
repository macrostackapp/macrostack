package com.macrostack.app.fusion

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

/** Runs row-band loops on all cores. */
class Parallel(val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) : AutoCloseable {

    private val executor: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "fusion").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /** Calls [body] for disjoint row ranges covering [0, rows), in parallel, and waits for all of them. */
    fun forRows(rows: Int, body: (from: Int, until: Int) -> Unit) {
        if (rows <= 0) return
        val bands = min(rows, threads * 4)
        if (bands == 1) {
            body(0, rows)
            return
        }
        val futures = (0 until bands).map { b ->
            executor.submit { body((rows.toLong() * b / bands).toInt(), (rows.toLong() * (b + 1) / bands).toInt()) }
        }
        try {
            futures.forEach { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
