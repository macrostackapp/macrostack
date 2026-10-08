package com.macrostack.app.stack

/**
 * Which frames of a burst to shoot again once it's over: those whose capture failed, and those whose
 * files the camera all dropped. A frame is listed once, however many times the camera reports it (a
 * multi-lens camera can report one failure for the request and another for the lens that took it).
 *
 * Thread-safe: called from the camera thread, read once the burst is over.
 */
class Reshoots(private val outputs: Int) {

    private val frames = sortedSetOf<Int>()
    private val lostFiles = HashMap<Int, Int>()

    @Synchronized
    fun failed(index: Int) {
        frames += index
    }

    /** One of frame [index]'s files was dropped by the camera. */
    @Synchronized
    fun lost(index: Int) {
        val lost = (lostFiles[index] ?: 0) + 1
        lostFiles[index] = lost
        if (lost >= outputs) frames += index
    }

    /** The frames to shoot again, in shooting order. */
    @Synchronized
    fun frames(): List<Int> = frames.toList()

    /** How many of frame [index]'s files were dropped; a re-shoot replaces them. */
    @Synchronized
    fun lostFiles(index: Int): Int = if (index in frames) lostFiles[index] ?: 0 else 0
}
