package com.frameender.pocketstash.data

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Small text formatters shared by Settings, the update pop-up and offline screens. */
object Format {
    fun bytes(n: Long): String = formatBytes(n.toDouble())

    fun count(n: Int): String = "%,d".format(n)

    /** "just now", "5 min ago", "3 h ago", "2 days ago" for an epoch-millis time. */
    fun agoMillis(epochMs: Long): String {
        val secs = Duration.between(Instant.ofEpochMilli(epochMs), Instant.now()).seconds.coerceAtLeast(0)
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 86_400 -> "${secs / 3600} h ago"
            secs < 86_400 * 30 -> "${secs / 86_400} day${if (secs / 86_400 == 1L) "" else "s"} ago"
            else -> Instant.ofEpochMilli(epochMs).toString().take(10)
        }
    }

    /** Same as [agoMillis] for an ISO-8601 timestamp (GitHub's published_at). */
    fun ago(iso: String): String =
        runCatching { agoMillis(OffsetDateTime.parse(iso).toInstant().toEpochMilli()) }.getOrDefault(iso.take(10))
}
