package com.frameender.pocketstash.player

/**
 * Stash's seek-bar previews: one sprite sheet (a grid of small frames) plus a WebVTT file that
 * says which rectangle of the sheet belongs to which stretch of time, e.g.
 *
 *     00:00:05.000 --> 00:00:10.000
 *     abc123_sprite.jpg#xywh=160,0,160,90
 *
 * This part is plain parsing (no Android), so it can be tested on its own.
 */
data class ScrubCue(val startMs: Long, val endMs: Long, val x: Int, val y: Int, val w: Int, val h: Int)

object ScrubThumbs {

    /** Parses Stash's sprite VTT. Lines that don't fit the pattern are skipped. */
    fun parse(vtt: String): List<ScrubCue> {
        val out = ArrayList<ScrubCue>()
        val lines = vtt.replace("\r", "").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val arrow = line.indexOf("-->")
            if (arrow > 0) {
                val start = time(line.substring(0, arrow).trim())
                val end = time(line.substring(arrow + 3).trim().substringBefore(' '))
                val target = lines.getOrNull(i + 1)?.trim().orEmpty()
                val rect = target.substringAfter("#xywh=", "").split(',').mapNotNull { it.trim().toIntOrNull() }
                if (start != null && end != null && rect.size == 4 && rect[2] > 0 && rect[3] > 0) {
                    out += ScrubCue(start, end, rect[0], rect[1], rect[2], rect[3])
                }
                i += 2
                continue
            }
            i++
        }
        return out.sortedBy { it.startMs }
    }

    /** "hh:mm:ss.mmm" or "mm:ss.mmm" → milliseconds. */
    fun time(s: String): Long? {
        val parts = s.split(':')
        if (parts.size !in 2..3) return null
        val secParts = parts.last().split('.', ',')
        val sec = secParts[0].toLongOrNull() ?: return null
        val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hour = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hour * 60 + min) * 60 + sec) * 1000 + ms
    }

    /** The cue covering [positionMs] (the nearest one if it falls in a gap or past the end). */
    fun cueAt(cues: List<ScrubCue>, positionMs: Long): ScrubCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cues[mid].startMs <= positionMs) lo = mid else hi = mid - 1
        }
        return cues[lo]
    }
}
