package com.frameender.pocketstash.player

import android.content.Context
import com.frameender.pocketstash.data.StashJson
import kotlinx.serialization.Serializable

/** What was picked for one scene last time: speed, audio track and subtitles. */
@Serializable
data class SceneChoice(
    val speed: Float = 1f,
    /** Audio track key from [trackKey], or null for the default. */
    val audio: String? = null,
    /** Subtitle track key from [trackKey], "off", or null (never chosen: off). */
    val subtitle: String? = null,
)

/** A track's identity that survives reloading: language, label and position in the list. */
fun trackKey(language: String?, label: String?, index: Int): String = "${language.orEmpty()}|${label.orEmpty()}|$index"

/** Best match for a remembered key: same label and language, else same language, else same position. */
fun matchTrack(key: String, tracks: List<Pair<String?, String?>>): Int? {
    val parts = key.split('|')
    if (parts.size != 3) return null
    val (lang, label, idx) = parts
    tracks.indexOfFirst { (l, n) -> l.orEmpty() == lang && n.orEmpty() == label }.takeIf { it >= 0 }?.let { return it }
    if (lang.isNotEmpty()) tracks.indexOfFirst { (l, _) -> l.orEmpty() == lang }.takeIf { it >= 0 }?.let { return it }
    return idx.toIntOrNull()?.takeIf { it in tracks.indices }
}

/** Per-scene choices, kept on the phone (the last few hundred scenes). */
class PlayerMemory(context: Context) {
    private val prefs = context.getSharedPreferences("player_memory", Context.MODE_PRIVATE)

    fun get(sceneId: String): SceneChoice =
        prefs.getString(sceneId, null)?.let { runCatching { StashJson.decodeFromString(SceneChoice.serializer(), it) }.getOrNull() }
            ?: SceneChoice()

    fun update(sceneId: String, transform: (SceneChoice) -> SceneChoice) {
        val next = transform(get(sceneId))
        val e = prefs.edit()
        if (next == SceneChoice()) e.remove(sceneId) else e.putString(sceneId, StashJson.encodeToString(SceneChoice.serializer(), next))
        e.apply()
        trim()
    }

    private fun trim() {
        val all = prefs.all
        if (all.size <= MAX) return
        // No timestamps kept: drop an arbitrary handful. Rarely reached, and only loses preferences.
        val e = prefs.edit()
        all.keys.take(all.size - MAX).forEach { e.remove(it) }
        e.apply()
    }

    private companion object {
        const val MAX = 500
    }
}
