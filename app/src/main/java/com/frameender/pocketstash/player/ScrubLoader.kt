package com.frameender.pocketstash.player

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A scene's seek-bar previews: the sprite sheet and where each moment sits on it. */
class ScrubData(val sheet: ImageBitmap, val cues: List<ScrubCue>)

/**
 * Loads the sprite VTT and sheet, from the server or from the copies saved with a download
 * (file:// URLs). Returns null when the scene has none (sprites not generated in Stash) or
 * they can't be read; the seek bar then just shows the time.
 */
suspend fun loadScrub(http: OkHttpClient, vttUrl: String?, spriteUrl: String?): ScrubData? = withContext(Dispatchers.IO) {
    if (vttUrl.isNullOrBlank() || spriteUrl.isNullOrBlank()) return@withContext null
    fun bytes(url: String): ByteArray? = runCatching {
        if (url.startsWith("file://")) {
            Uri.parse(url).path?.let { File(it).readBytes() }
        } else {
            http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }
    }.getOrNull()
    val cues = bytes(vttUrl)?.toString(Charsets.UTF_8)?.let { ScrubThumbs.parse(it) }.orEmpty()
    if (cues.isEmpty()) return@withContext null
    val sheet = bytes(spriteUrl)?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
        ?: return@withContext null
    ScrubData(sheet.asImageBitmap(), cues)
}
