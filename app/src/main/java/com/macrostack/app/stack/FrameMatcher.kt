package com.macrostack.app.stack

/**
 * Pairs each incoming JPEG with the frame number it was shot for.
 *
 * A JPEG's timestamp normally equals the timestamp reported when its capture started, so that
 * is tried first. If a device doesn't honour that, JPEGs are matched in the order captures were
 * submitted — captures are issued one at a time, so their JPEGs arrive in that order.
 *
 * Thread-safe: called from the camera thread, the JPEG thread and the main thread.
 */
class FrameMatcher {

    private class Pending(val index: Int) {
        var timestamp: Long = NO_TIMESTAMP
    }

    private val pending = ArrayDeque<Pending>()

    /** Set once a JPEG has matched a capture timestamp exactly, proving timestamps work on this phone. */
    private var timestampsReliable = false

    @Synchronized
    fun submitted(index: Int) {
        pending.addLast(Pending(index))
    }

    @Synchronized
    fun started(index: Int, timestamp: Long) {
        pending.lastOrNull { it.index == index && it.timestamp == NO_TIMESTAMP }?.timestamp = timestamp
    }

    /** The capture for [index] failed and produced no image. */
    @Synchronized
    fun failed(index: Int) {
        removeLatest(index)
    }

    /** The JPEG buffer for [index] was dropped by the camera. Returns false if it was already resolved. */
    @Synchronized
    fun lost(index: Int): Boolean = removeLatest(index)

    /**
     * Returns the frame index for a JPEG with [timestamp], or null to drop it — nothing outstanding,
     * or (once timestamps are known to work) a late JPEG from a capture that was reported failed and
     * has already been queued for a re-shoot. Matching that one by order would shift every later frame.
     */
    @Synchronized
    fun onJpeg(timestamp: Long): Int? {
        val exact = pending.firstOrNull { it.timestamp == timestamp }
        if (exact != null) {
            timestampsReliable = true
            pending.remove(exact)
            return exact.index
        }
        if (timestampsReliable) return null
        val next = pending.firstOrNull() ?: return null
        pending.remove(next)
        return next.index
    }

    @Synchronized
    fun outstanding(): Int = pending.size

    private fun removeLatest(index: Int): Boolean {
        val entry = pending.lastOrNull { it.index == index } ?: return false
        pending.remove(entry)
        return true
    }

    private companion object {
        const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
