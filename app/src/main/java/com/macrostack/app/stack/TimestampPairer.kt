package com.macrostack.app.stack

/**
 * Joins two things that belong to the same sensor frame but arrive separately and in either
 * order — here a RAW image and the capture metadata needed to turn it into a DNG.
 *
 * Thread-safe; [onPair] and [onDiscard] run on the calling thread, outside the lock.
 */
class TimestampPairer<A : Any, B : Any>(
    private val onPair: (A, B) -> Unit,
    /** Receives [A]s that will never be paired, so their resources can be released. */
    private val onDiscard: (A) -> Unit,
) {
    private val firsts = HashMap<Long, A>()
    private val seconds = HashMap<Long, B>()
    private val dropped = HashSet<Long>()

    fun addFirst(timestamp: Long, a: A) {
        var partner: B? = null
        var discard = false
        synchronized(this) {
            if (dropped.remove(timestamp)) {
                discard = true
            } else {
                partner = seconds.remove(timestamp)
                if (partner == null) firsts[timestamp] = a
            }
        }
        if (discard) onDiscard(a) else partner?.let { onPair(a, it) }
    }

    fun addSecond(timestamp: Long, b: B) {
        val partner: A?
        synchronized(this) {
            partner = firsts.remove(timestamp)
            if (partner == null) seconds[timestamp] = b
        }
        partner?.let { onPair(it, b) }
    }

    /** The frame at [timestamp] was abandoned: discard its [A] now, or as soon as it turns up. */
    fun drop(timestamp: Long) {
        val orphan: A?
        synchronized(this) {
            seconds.remove(timestamp)
            orphan = firsts.remove(timestamp)
            if (orphan == null) dropped += timestamp
        }
        orphan?.let(onDiscard)
    }

    /** Discards everything still waiting for a partner. */
    fun discardAll() {
        val leftovers: List<A>
        synchronized(this) {
            leftovers = firsts.values.toList()
            firsts.clear()
            seconds.clear()
            dropped.clear()
        }
        leftovers.forEach(onDiscard)
    }
}
