package com.frameender.pocketstash.data

import java.io.File
import java.security.MessageDigest

/**
 * Saved copies of Stash's GraphQL answers, so screens you've visited (or saved for offline)
 * still open when the server can't be reached.
 *
 * Stash's API is GraphQL over POST, which OkHttp's HTTP cache never stores, so the app keeps
 * its own: one file per distinct (server, API key, query, variables), named by a SHA-256 of
 * those. Only read queries are stored, never mutations. Oldest files are removed once the
 * total passes [maxBytes].
 */
class ResponseCache(private val dir: File, private val maxBytes: Long = 128L * 1024 * 1024) {

    init { dir.mkdirs() }

    @Volatile private var writesSinceTrim = 0

    fun key(server: String, apiKey: String, query: String, variables: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in listOf(server, apiKey, query, variables)) {
            md.update(part.toByteArray())
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): String? {
        val f = File(dir, key)
        if (!f.exists()) return null
        return runCatching { f.readText().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    fun put(key: String, body: String) {
        runCatching {
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(File(dir, key))) tmp.delete()
        }
        if (++writesSinceTrim >= 25) {
            writesSinceTrim = 0
            trim()
        }
    }

    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun count(): Int = dir.listFiles()?.size ?: 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Drops least-recently-used files until the total fits. */
    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
