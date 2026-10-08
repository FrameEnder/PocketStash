package com.frameender.pocketstash.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.frameender.pocketstash.MainActivity
import com.frameender.pocketstash.R
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.model.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToLong

/** One scene saved (or being saved) to the phone for offline playback. */
@Serializable
data class SceneDownload(
    val sceneId: String,
    val title: String,
    /** "original", or a Stash streaming resolution like "STANDARD_HD". */
    val quality: String,
    val qualityLabel: String,
    val url: String,
    val fileName: String,
    val status: String = QUEUED,
    val bytes: Long = 0,
    /** Expected size, or 0 when unknown (live MP4 conversions). */
    val total: Long = 0,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    /** The scene's file name and length when downloaded, so a different server's scene 12 isn't mistaken for this one. */
    val fileKey: String = "",
    val screenshot: String? = null,
) {
    val done: Boolean get() = status == DONE
    val progress: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** A quality you can pick when downloading a scene. */
data class DownloadOption(
    val key: String,
    val label: String,
    val detail: String,
    val url: String,
    val extension: String,
    val knownSize: Long,
)

/** Identifies a scene's video file across servers: file name + length in whole seconds. */
fun sceneFileKey(s: Scene): String {
    val f = s.files.firstOrNull() ?: return ""
    return (f.basename ?: f.path?.substringAfterLast('/') ?: "") + "|" + (f.duration?.roundToLong() ?: 0L)
}

/**
 * Scene downloads: a queue worked through by a WorkManager worker in the foreground (with a
 * progress notification), so downloads carry on when you leave the app.
 *
 * Videos go to the app's own Movies folder (Android/data/…/files/Movies/PocketStash), so no
 * storage permission is needed and they're removed with the app. Original files resume after
 * an interruption; MP4 conversions are made live by Stash and restart instead.
 *
 * Each download also saves everything offline mode needs to show the scene: its details,
 * the performers, studio, tags and groups it mentions, and their pictures (see OfflineLibrary).
 */
class SceneDownloads(
    private val context: Context,
    private val connection: Connection,
    private val repo: StashRepository,
    private val library: OfflineLibrary,
    private val scope: CoroutineScope,
) {
    private val indexFile = File(context.filesDir, "downloads.json")
    private val listSer = ListSerializer(SceneDownload.serializer())

    val dir: File = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "movies"))
        .resolve("PocketStash").apply { mkdirs() }

    private val _items = MutableStateFlow<List<SceneDownload>>(load())
    val items: StateFlow<List<SceneDownload>> = _items

    /** Ids of scenes whose video is on the phone. */
    val doneIds: Set<String> get() = _items.value.filter { it.done && File(dir, it.fileName).exists() }.map { it.sceneId }.toSet()

    /** "running", "waiting" (for Wi-Fi or the network) or "idle". */
    val workState: StateFlow<String> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(WORK)
        .map { infos ->
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> "running"
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> "waiting"
                else -> "idle"
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, "idle")

    /** Downloads removed while running; the worker checks this between chunks. */
    private val cancelled = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun load(): List<SceneDownload> = runCatching {
        if (indexFile.exists()) StashJson.decodeFromString(listSer, indexFile.readText()) else emptyList()
    }.getOrDefault(emptyList()).map {
        // A download that was mid-way when the app died goes back in the queue.
        if (it.status == SceneDownload.RUNNING) it.copy(status = SceneDownload.QUEUED) else it
    }

    private fun persist() {
        val text = StashJson.encodeToString(listSer, _items.value)
        scope.launch(Dispatchers.IO) {
            synchronized(indexFile) {
                val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
            }
        }
    }

    private fun update(sceneId: String, persistNow: Boolean = true, transform: (SceneDownload) -> SceneDownload) {
        _items.value = _items.value.map { if (it.sceneId == sceneId) transform(it) else it }
        if (persistNow) persist()
    }

    fun get(sceneId: String): SceneDownload? = _items.value.firstOrNull { it.sceneId == sceneId }

    /** The downloaded video for this scene, if it's complete. */
    fun localFile(sceneId: String): File? {
        val d = get(sceneId)?.takeIf { it.done } ?: return null
        return File(dir, d.fileName).takeIf { it.exists() }
    }

    /** True when this exact scene (same file) is downloaded. Online, [fileKey] guards against id clashes. */
    fun isDownloaded(sceneId: String, fileKey: String? = null): Boolean {
        val d = get(sceneId)?.takeIf { it.done } ?: return false
        return fileKey == null || d.fileKey.isEmpty() || fileKey.isEmpty() || d.fileKey == fileKey
    }

    fun totalBytes(): Long = _items.value.sumOf { d -> File(dir, d.fileName).takeIf { it.exists() }?.length() ?: 0L }

    fun freeBytes(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    // ------------------------------------------------------------------ choosing a quality

    /** What this scene can be downloaded as: the original file, plus Stash's MP4 conversions. */
    fun options(scene: Scene): List<DownloadOption> {
        val out = ArrayList<DownloadOption>()
        val f = scene.files.firstOrNull()
        val ext = (f?.format?.lowercase()?.takeIf { it.length in 2..5 }
            ?: f?.basename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 }
            ?: "mp4")
        scene.paths.stream?.let { raw ->
            val url = connection.media(raw) ?: raw
            out += DownloadOption(
                "original", "Original file",
                listOfNotNull(
                    f?.height?.let { resolutionLabel(it) },
                    f?.videoCodec?.uppercase(),
                    f?.size?.let { formatBytes(it.toDouble()) },
                ).joinToString(" · ") + " · resumes if interrupted",
                url, ext, f?.size ?: 0L,
            )
        }
        for (st in scene.streams) {
            val mime = st.mimeType ?: continue
            if (!mime.contains("mp4")) continue
            val res = st.url.toHttpUrlOrNull()?.queryParameter("resolution") ?: continue
            val label = when (res) {
                "ORIGINAL" -> "MP4 · same resolution"
                "FOUR_K" -> "MP4 · 4K"
                "FULL_HD" -> "MP4 · 1080p"
                "STANDARD_HD" -> "MP4 · 720p"
                "STANDARD" -> "MP4 · 480p"
                "LOW" -> "MP4 · 240p"
                else -> st.label ?: res
            }
            out += DownloadOption(
                res, label,
                if (res == "ORIGINAL") "Plays on any phone; Stash converts it while downloading (slower)"
                else "Smaller file; Stash converts it while downloading (slower)",
                connection.media(st.url) ?: st.url, "mp4", 0L,
            )
        }
        return out
    }

    /** The option with this key, or the original file if the scene doesn't offer it (used by "download the whole list"). */
    fun optionFor(scene: Scene, key: String): DownloadOption? =
        options(scene).firstOrNull { it.key == key } ?: options(scene).firstOrNull()

    // ------------------------------------------------------------------ queue actions

    fun enqueue(scene: Scene, option: DownloadOption) {
        val item = SceneDownload(
            sceneId = scene.id,
            title = scene.displayTitle,
            quality = option.key,
            qualityLabel = option.label,
            url = option.url,
            fileName = "scene-${scene.id}-${option.key.lowercase()}.${option.extension}",
            total = option.knownSize,
            fileKey = sceneFileKey(scene),
            screenshot = scene.paths.screenshot,
        )
        get(scene.id)?.let { old -> if (old.fileName != item.fileName) deleteFiles(old) }
        _items.value = _items.value.filterNot { it.sceneId == scene.id } + item
        cancelled.remove(scene.id)
        persist()
        // Save the details now, while the server is surely reachable.
        scope.launch(Dispatchers.IO) { runCatching { captureMetadata(scene.id) } }
        start()
    }

    fun retry(sceneId: String) {
        update(sceneId) { it.copy(status = SceneDownload.QUEUED, error = null) }
        start()
    }

    /** Removes a download (stopping it if it's running) and deletes its file. */
    fun delete(sceneId: String) {
        val d = get(sceneId) ?: return
        cancelled.add(sceneId)
        deleteFiles(d)
        _items.value = _items.value.filterNot { it.sceneId == sceneId }
        persist()
        library.releaseDownload(sceneId)
    }

    private fun deleteFiles(d: SceneDownload) {
        File(dir, d.fileName).delete()
        File(dir, d.fileName + ".part").delete()
    }

    fun deleteAll() {
        val gone = _items.value
        gone.forEach { cancelled.add(it.sceneId); deleteFiles(it) }
        _items.value = emptyList()
        gone.forEach { library.releaseDownload(it.sceneId) }
        persist()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Stops downloading; queued items wait until [resumeAll]. */
    fun pauseAll() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        _items.value = _items.value.map { if (it.status == SceneDownload.RUNNING) it.copy(status = SceneDownload.QUEUED) else it }
        persist()
    }

    fun resumeAll() = start()

    /** Starts (or re-plans) the worker with the current Wi-Fi rule. */
    fun start() {
        if (_items.value.none { it.status == SceneDownload.QUEUED }) return
        val wifiOnly = connection.settings.downloadWifiOnly
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    // ------------------------------------------------------------------ details for offline mode

    /** Stores the scene and everything it mentions, with their pictures, in the offline library. */
    suspend fun captureMetadata(sceneId: String) {
        if (connection.offline.value) return
        val scene = repo.rawDetail(EntityKind.SCENES, sceneId)
        // Owned by this download: deleting it removes whatever nothing else needs.
        val owner = "dl:$sceneId"
        library.put(EntityKind.SCENES, scene, owner)
        val http = connection.http
        val resolve = { raw: String -> connection.media(raw) }
        with(OfflineQuery) {
            library.saveImage(scene.o("paths")?.s("screenshot"), http, resolve)
            scene.a("scene_markers").forEach { library.saveImage(it.s("screenshot"), http, resolve) }
            suspend fun related(kind: EntityKind, id: String?, imageField: String) {
                if (id == null) return
                runCatching {
                    val o = repo.rawDetail(kind, id)
                    library.put(kind, o, owner)
                    library.saveImage(o.s(imageField), http, resolve)
                }
            }
            scene.a("performers").forEach { related(EntityKind.PERFORMERS, it.s("id"), "image_path") }
            related(EntityKind.STUDIOS, scene.o("studio")?.s("id"), "image_path")
            scene.a("tags").forEach { related(EntityKind.TAGS, it.s("id"), "image_path") }
            scene.a("groups").forEach { related(EntityKind.GROUPS, it.o("group")?.s("id"), "front_image_path") }
        }
    }

    // ------------------------------------------------------------------ the worker's loop

    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of scenes downloading for offline playback"
                },
            )
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, "downloads")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(d: SceneDownload, waiting: Int, worker: CoroutineWorker) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(d.title)
            .setContentText(
                listOfNotNull(
                    if (d.total > 0) "${formatBytes(d.bytes.toDouble())} of ${formatBytes(d.total.toDouble())}"
                    else formatBytes(d.bytes.toDouble()),
                    d.qualityLabel,
                    if (waiting > 0) "$waiting more queued" else null,
                ).joinToString(" · "),
            )
            .setProgress(100, ((d.progress ?: 0f) * 100).toInt(), d.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .addAction(0, "Pause", WorkManager.getInstance(context).createCancelPendingIntent(worker.id))
            .build()

    private fun foregroundInfo(d: SceneDownload, waiting: Int, worker: CoroutineWorker): ForegroundInfo {
        val n = progressNotification(d, waiting, worker)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_PROGRESS, n)
    }

    private fun notifyFinished(done: Int, failed: Int) {
        if (done + failed == 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (done == 1) "1 scene downloaded" else "$done scenes downloaded")
            .setContentText(if (failed > 0) "$failed couldn't be downloaded. Open Downloads to retry." else "Ready to watch offline")
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_DONE, n) }
    }

    /** Works through the queue one scene at a time. Called by [DownloadWorker]. */
    suspend fun runQueue(worker: CoroutineWorker): Boolean = withContext(Dispatchers.IO) {
        ensureChannel()
        var done = 0
        var failed = 0
        while (!worker.isStopped) {
            val next = _items.value.firstOrNull { it.status == SceneDownload.QUEUED } ?: break
            update(next.sceneId) { it.copy(status = SceneDownload.RUNNING, error = null) }
            val waiting = { _items.value.count { it.status == SceneDownload.QUEUED } }
            runCatching { worker.setForeground(foregroundInfo(get(next.sceneId) ?: next, waiting(), worker)) }
            try {
                download(next, worker) { d -> runCatching { worker.setForeground(foregroundInfo(d, waiting(), worker)) } }
                if (get(next.sceneId) != null) {
                    runCatching { captureMetadata(next.sceneId) }
                    done++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Paused, or the network rule stopped us: back in the queue for next time.
                if (get(next.sceneId)?.status == SceneDownload.RUNNING) update(next.sceneId) { it.copy(status = SceneDownload.QUEUED) }
                throw e
            } catch (e: Exception) {
                if (worker.isStopped) {
                    if (get(next.sceneId)?.status == SceneDownload.RUNNING) update(next.sceneId) { it.copy(status = SceneDownload.QUEUED) }
                    break
                }
                if (get(next.sceneId) != null) {
                    update(next.sceneId) { it.copy(status = SceneDownload.FAILED, error = e.message ?: e.javaClass.simpleName) }
                    failed++
                }
            }
        }
        notifyFinished(done, failed)
        true
    }

    private suspend fun download(d: SceneDownload, worker: CoroutineWorker, onProgress: suspend (SceneDownload) -> Unit) {
        val target = File(dir, d.fileName)
        val part = File(dir, d.fileName + ".part")
        val resumable = d.quality == "original"
        if (!resumable) part.delete()
        var offset = if (resumable && part.exists()) part.length() else 0L

        if (d.total > 0 && d.total - offset > freeBytes()) throw IOException("Not enough space on the phone")

        val req = Request.Builder().url(d.url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        connection.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Stash answered HTTP ${r.code}")
            if (offset > 0 && r.code != 206) offset = 0L // server ignored the range: start over
            val body = r.body ?: throw IOException("Empty response")
            val length = body.contentLength()
            val total = when {
                length > 0 -> offset + length
                else -> d.total
            }
            update(d.sceneId, persistNow = false) { it.copy(bytes = offset, total = total) }
            FileOutputStream(part, offset > 0).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var written = offset
                    var lastUi = 0L
                    var lastSave = 0L
                    while (true) {
                        if (worker.isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
                        if (d.sceneId in cancelled) {
                            part.delete()
                            return
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 700) {
                            lastUi = now
                            val save = now - lastSave > 5000
                            if (save) lastSave = now
                            update(d.sceneId, persistNow = save) { it.copy(bytes = written) }
                            get(d.sceneId)?.let { onProgress(it) }
                        }
                    }
                }
            }
        }
        if (d.sceneId in cancelled) { part.delete(); return }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the file")
        update(d.sceneId) {
            it.copy(status = SceneDownload.DONE, bytes = target.length(), total = target.length(), finishedAt = System.currentTimeMillis())
        }
    }

    companion object {
        const val WORK = "pocketstash-downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIF_PROGRESS = 4830
        private const val NOTIF_DONE = 4831
    }
}

/** Runs the download queue in the foreground until it's empty (or paused / off Wi-Fi). */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.downloads.runQueue(this)
        return Result.success()
    }
}
