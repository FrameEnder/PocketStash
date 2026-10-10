package com.frameender.pocketstash.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One scene in the play queue (enough to show it in "Up next" without asking the server). */
data class QueueEntry(val id: String, val title: String, val image: String? = null)

/**
 * The scenes "Play all" / "Shuffle" lined up, shared between the list that started it and the
 * player. Plain order is kept so shuffle can be turned off again without losing the place.
 */
object PlayQueue {
    var entries by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var shuffled by mutableStateOf(false)
        private set
    /** Go on to the next scene when one ends. */
    var autoNext by mutableStateOf(true)
    /** Name of the list it came from, for the "Up next" header. */
    var source by mutableStateOf("")
        private set

    private var original: List<QueueEntry> = emptyList()

    val active: Boolean get() = entries.isNotEmpty()
    val current: QueueEntry? get() = entries.getOrNull(index)
    val hasNext: Boolean get() = index < entries.size - 1
    val hasPrevious: Boolean get() = index > 0

    fun start(list: List<QueueEntry>, startAt: Int = 0, shuffle: Boolean = false, from: String = "") {
        val clean = list.distinctBy { it.id }
        original = clean
        source = from
        shuffled = shuffle
        if (shuffle) {
            entries = clean.shuffled()
            index = 0
        } else {
            entries = clean
            index = startAt.coerceIn(0, (clean.size - 1).coerceAtLeast(0))
        }
    }

    fun clear() {
        entries = emptyList()
        original = emptyList()
        index = 0
        shuffled = false
        source = ""
    }

    fun next(): QueueEntry? {
        if (!hasNext) return null
        index++
        return current
    }

    fun previous(): QueueEntry? {
        if (!hasPrevious) return null
        index--
        return current
    }

    fun jumpTo(i: Int): QueueEntry? {
        if (i !in entries.indices) return null
        index = i
        return current
    }

    /** Drops a scene that can't be played (e.g. offline and not downloaded). */
    fun remove(id: String) {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return
        entries = entries.toMutableList().also { it.removeAt(i) }
        original = original.filterNot { it.id == id }
        if (i < index) index--
        index = index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    }

    /**
     * Shuffle on: the current scene stays put and everything else is shuffled after it.
     * Shuffle off: back to the list's own order, still on the current scene.
     */
    fun setShuffle(on: Boolean) {
        if (on == shuffled || entries.isEmpty()) return
        val cur = current
        shuffled = on
        if (on) {
            val rest = entries.filter { it.id != cur?.id }.shuffled()
            entries = listOfNotNull(cur) + rest
            index = 0
        } else {
            entries = original
            index = entries.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        }
    }
}
