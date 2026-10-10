package com.frameender.pocketstash.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.frameender.pocketstash.R
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.EditKind
import com.frameender.pocketstash.data.formatDuration
import com.frameender.pocketstash.data.model.Marker
import com.frameender.pocketstash.data.model.Scene
import com.frameender.pocketstash.data.model.StreamEndpoint
import com.frameender.pocketstash.security.AppLockGate
import com.frameender.pocketstash.security.appLock
import com.frameender.pocketstash.ui.edit.EditScreen
import com.frameender.pocketstash.ui.edit.EditViewModel
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.PocketStashTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Full-screen ExoPlayer for Stash scenes (or a direct clip URL).
 * - direct stream first, falls back through Stash's transcode endpoints on error
 * - resume point, play count and watch time synced back to Stash
 * - double-tap left/right to skip 5s back / 15s ahead, swipes for brightness and volume,
 *   hold for 2×, mute button
 * - seek-bar previews from Stash's sprite sheets, marker chapters with previous/next,
 *   A–B loop, frame step when paused
 * - play queue ("Play all" / "Shuffle") with auto-next and an "Up next" sheet
 * - subtitles and audio tracks, speed, all remembered per scene; sleep timer
 * - picture-in-picture with back / play-pause / forward (or next scene) buttons
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SCENE = "scene_id"
        private const val EXTRA_START = "start_seconds"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_QUEUE = "queue"

        private const val ACTION_PIP = "com.frameender.pocketstash.PIP_CONTROL"
        private const val EXTRA_PIP_CMD = "cmd"
        private const val PIP_BACK = 1
        private const val PIP_TOGGLE = 2
        private const val PIP_FORWARD = 3
        private const val PIP_NEXT = 4

        fun sceneIntent(context: Context, sceneId: String, startSeconds: Double?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_SCENE, sceneId)
                .apply { if (startSeconds != null) putExtra(EXTRA_START, startSeconds) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Plays [PlayQueue] from its current scene. */
        fun queueIntent(context: Context): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_QUEUE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun urlIntent(context: Context, url: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private lateinit var player: ExoPlayer
    private lateinit var memory: PlayerMemory
    private var sceneId: String? = null

    // UI state
    private var title by mutableStateOf("")
    private var streams by mutableStateOf<List<StreamEndpoint>>(emptyList())
    private var streamIndex by mutableStateOf(0)
    private var markers by mutableStateOf<List<Marker>>(emptyList())
    private var inPip by mutableStateOf(false)
    private var speed by mutableStateOf(1f)
    private var scrub by mutableStateOf<ScrubData?>(null)
    private var tracks by mutableStateOf(Tracks.EMPTY)
    private var frameMs by mutableLongStateOf(33L)
    private var queueMode by mutableStateOf(false)
    private var showUpNext by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    /** Subtitle files offered for the current scene (side-loaded into the media item). */
    private var captions: List<MediaItem.SubtitleConfiguration> = emptyList()

    /** Remembered choices are applied once per scene, when its tracks are known. */
    private var tracksAppliedFor: String? = null

    /** The controls' state object, so a new scene can reset its A–B loop. */
    private var ui: PlaybackUi? = null

    // Sleep timer: an end time (elapsedRealtime) or "at the end of this scene".
    private var sleepAt by mutableStateOf<Long?>(null)
    private var sleepEndOfScene by mutableStateOf(false)
    private var sleepLeftMs by mutableLongStateOf(0L)

    /** Open "add marker" form (shown over the video), or null. */
    private var markerForm by mutableStateOf<EditViewModel?>(null)

    // Activity tracking
    private var playedMs = 0L
    private var unsentPlayedMs = 0L
    private var playCounted = false
    private var trackerJob: Job? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_PIP_CMD, 0)) {
                PIP_BACK -> player.seekTo((player.currentPosition - SKIP_BACK_MS).coerceAtLeast(0L))
                PIP_TOGGLE -> if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
                PIP_FORWARD -> {
                    val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + SKIP_FORWARD_MS).coerceAtMost(dur))
                }
                PIP_NEXT -> nextScene()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        memory = PlayerMemory(this)

        val conn = container.connection
        // Local files (downloads, saved subtitles) through the default source, everything else
        // through the authenticated OkHttp client.
        val dataSource = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(conn.http))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(listener)
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            PocketStashTheme {
                // Locked (e.g. back from the background): stop playback until it's unlocked.
                val locked by appLock.locked.collectAsState()
                LaunchedEffect(locked) { if (locked) player.pause() }
                AppLockGate { PlayerScreen() }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Already open (single task): switch to what was asked for instead of ignoring it.
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_SCENE)
        val url = intent.getStringExtra(EXTRA_URL)
        when {
            intent.getBooleanExtra(EXTRA_QUEUE, false) && PlayQueue.current != null -> {
                queueMode = true
                loadScene(PlayQueue.current!!.id, null)
            }
            id != null -> {
                queueMode = false
                loadScene(id, if (intent.hasExtra(EXTRA_START)) intent.getDoubleExtra(EXTRA_START, 0.0) else null)
            }
            url != null -> {
                queueMode = false
                sceneId = null
                title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
            }
            else -> finish()
        }
    }

    // ------------------------------------------------------------ loading a scene

    private fun loadScene(id: String, start: Double?) {
        // Leaving the previous scene: send its watch time first.
        val previous = sceneId
        if (previous != null && previous != id) {
            val pos = player.currentPosition
            val dur = player.duration
            val delta = unsentPlayedMs
            lifecycleScope.launch { withContext(NonCancellable) { sendActivity(previous, pos, dur, delta, ended = false) } }
        }
        sceneId = id
        playedMs = 0L
        unsentPlayedMs = 0L
        playCounted = false
        markers = emptyList()
        scrub = null
        tracksAppliedFor = null
        ui?.let { it.loopA = null; it.loopB = null }
        player.repeatMode = Player.REPEAT_MODE_OFF

        lifecycleScope.launch {
            try {
                val scene = container.repository.scene(id)
                if (sceneId != id) return@launch // another scene was picked meanwhile
                val conn = container.connection
                title = scene.displayTitle
                markers = scene.markers.sortedBy { it.seconds }
                frameMs = scene.files.firstOrNull()?.frameRate?.takeIf { it > 1 }?.let { (1000.0 / it).toLong() } ?: 33L

                // The downloaded copy first (no network needed), then, online, the direct file
                // and whatever Stash offers (HLS/DASH/MP4/WebM transcodes).
                val downloads = container.downloads
                val local = downloads.localFile(id)?.takeIf { container.repository.isDownloaded(scene) }?.let { f ->
                    StreamEndpoint(Uri.fromFile(f).toString(), null, "Downloaded · " + (downloads.get(id)?.qualityLabel ?: "on this phone"))
                }
                val offline = container.repository.isOffline
                val server = if (offline) emptyList() else {
                    val direct = scene.paths.stream?.let { StreamEndpoint(it, null, "Direct") }
                    (listOfNotNull(direct) + scene.streams).map { it.copy(url = conn.media(it.url) ?: it.url) }
                }
                streams = (listOfNotNull(local) + server).distinctBy { it.url }
                if (streams.isEmpty()) {
                    val why = if (offline) "isn't downloaded (offline mode plays only downloads)" else "has no playable streams"
                    if (queueMode && skipUnplayable(id, "“${scene.displayTitle}” $why: skipped")) return@launch
                    toast("This scene $why")
                    finish(); return@launch
                }
                captions = captionsFor(scene, offline)

                // What was picked for this scene last time.
                val choice = memory.get(id)
                speed = choice.speed
                player.setPlaybackSpeed(speed)
                // Subtitles start off unless they were turned on for this scene.
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice.subtitle == null || choice.subtitle == "off")
                    .build()

                val settings = container.settings.value
                val startAt = start ?: if (settings?.resumePlayback != false) scene.resumeTime else null
                val duration = scene.duration
                val resumeMs = startAt
                    ?.takeIf { it > 0 && (duration == null || it < duration - 5) }
                    ?.let { (it * 1000).toLong() } ?: 0L
                play(0, resumeMs)

                // Seek-bar previews (from the server, or saved with the download).
                val vtt = conn.media(scene.paths.vtt)
                val sprite = conn.media(scene.paths.sprite)
                launch { loadScrub(conn.http, vtt, sprite)?.let { if (sceneId == id) scrub = it } }
            } catch (e: Exception) {
                if (queueMode && skipUnplayable(id, "Couldn't load a scene: skipped")) return@launch
                toast(e.message ?: "Couldn't load scene")
                finish()
            }
        }
    }

    /** In a queue: drop a scene that can't play and go on. False if nothing is left. */
    private fun skipUnplayable(id: String, message: String): Boolean {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val wasIndex = PlayQueue.index
        PlayQueue.remove(id)
        val next = PlayQueue.entries.getOrNull(wasIndex.coerceAtMost(PlayQueue.entries.size - 1)) ?: return false
        PlayQueue.jumpTo(PlayQueue.entries.indexOf(next))
        loadScene(next.id, null)
        return true
    }

    /** The scene's subtitles: saved files for a download, else Stash's caption endpoint (online). */
    private fun captionsFor(scene: Scene, offline: Boolean): List<MediaItem.SubtitleConfiguration> {
        val list = scene.captions.orEmpty()
        if (list.isEmpty()) return emptyList()
        val conn = container.connection
        val base = scene.paths.caption?.let { conn.media(it) ?: it }?.toHttpUrlOrNull()
        return list.mapNotNull { c ->
            val saved = container.downloads.captionFile(scene.id, c.languageCode, c.captionType).takeIf { it.exists() }
            val uri = when {
                saved != null -> Uri.fromFile(saved)
                offline || base == null -> return@mapNotNull null
                else -> Uri.parse(base.newBuilder().setQueryParameter("lang", c.languageCode).setQueryParameter("type", c.captionType).build().toString())
            }
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(MimeTypes.TEXT_VTT) // Stash converts every caption format to WebVTT
                .setLanguage(c.languageCode)
                .setLabel(languageName(c.languageCode) + if (list.count { it.languageCode == c.languageCode } > 1) " (${c.captionType})" else "")
                .build()
        }
    }

    private fun languageName(code: String): String =
        runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault()) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code.uppercase()

    private fun play(index: Int, positionMs: Long) {
        streamIndex = index
        val s = streams[index]
        val builder = MediaItem.Builder().setUri(s.url).setSubtitleConfigurations(captions)
        mimeFor(s)?.let { builder.setMimeType(it) }
        player.setMediaItem(builder.build(), positionMs)
        player.prepare()
        player.playWhenReady = true
        startTracker()
    }

    private fun mimeFor(s: StreamEndpoint): String? {
        val m = s.mimeType?.lowercase() ?: return when {
            s.url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        return when {
            "mpegurl" in m -> MimeTypes.APPLICATION_M3U8
            "dash" in m -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // ------------------------------------------------------------ queue

    private fun nextScene() {
        val next = PlayQueue.next() ?: return
        loadScene(next.id, null)
    }

    private fun previousScene() {
        // Like music players: a few seconds in, "previous" restarts this scene first.
        if (player.currentPosition > 5_000 || !PlayQueue.hasPrevious) {
            player.seekTo(0); return
        }
        val prev = PlayQueue.previous() ?: return
        loadScene(prev.id, null)
    }

    // ------------------------------------------------------------ tracks (subtitles / audio)

    private fun groups(type: Int) = tracks.groups.filter { it.type == type && it.isSupported }

    private fun describe(g: Tracks.Group, i: Int, type: Int): String {
        val f = g.getTrackFormat(0)
        return f.label ?: f.language?.let { languageName(it) } ?: (if (type == C.TRACK_TYPE_AUDIO) "Track ${i + 1}" else "Subtitles ${i + 1}")
    }

    private fun keyOf(g: Tracks.Group, i: Int): String = trackKey(g.getTrackFormat(0).language, g.getTrackFormat(0).label, i)

    private fun selectText(i: Int?, remember: Boolean = true) {
        val text = groups(C.TRACK_TYPE_TEXT)
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (i == null || i !in text.indices) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(text[i].mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = b.build()
        if (remember) sceneId?.let { id -> memory.update(id) { it.copy(subtitle = if (i == null) "off" else keyOf(text[i], i)) } }
    }

    private fun selectAudio(i: Int, remember: Boolean = true) {
        val audio = groups(C.TRACK_TYPE_AUDIO)
        if (i !in audio.indices) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(TrackSelectionOverride(audio[i].mediaTrackGroup, 0))
            .build()
        if (remember) sceneId?.let { id -> memory.update(id) { it.copy(audio = keyOf(audio[i], i)) } }
    }

    /** First time a scene's tracks are known: put back the audio and subtitles chosen last time. */
    private fun applyRememberedTracks() {
        val id = sceneId ?: return
        if (tracksAppliedFor == id || tracks.groups.isEmpty()) return
        tracksAppliedFor = id
        val choice = memory.get(id)
        choice.audio?.let { key ->
            val audio = groups(C.TRACK_TYPE_AUDIO)
            if (audio.size > 1) matchTrack(key, audio.map { it.getTrackFormat(0).language to it.getTrackFormat(0).label })?.let { selectAudio(it, remember = false) }
        }
        val sub = choice.subtitle
        if (sub != null && sub != "off") {
            val text = groups(C.TRACK_TYPE_TEXT)
            matchTrack(sub, text.map { it.getTrackFormat(0).language to it.getTrackFormat(0).label })?.let { selectText(it, remember = false) }
        }
    }

    private fun changeSpeed(s: Float) {
        speed = s
        player.setPlaybackSpeed(s)
        sceneId?.let { id -> memory.update(id) { it.copy(speed = s) } }
    }

    // ------------------------------------------------------------ player events

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Direct play failed (codec/container the device can't decode) —
            // walk down Stash's transcode list.
            val next = streamIndex + 1
            if (sceneId != null && next < streams.size) {
                val pos = player.currentPosition
                toast("Can't play ${streams[streamIndex].label ?: "stream"} — trying ${streams[next].label ?: "transcode"}")
                play(next, pos)
            } else {
                toast("Playback error: ${error.errorCodeName}")
            }
        }

        override fun onTracksChanged(t: Tracks) {
            tracks = t
            applyRememberedTracks()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            updatePipParams()
            if (orientationLocked) return
            requestedOrientation = if (videoSize.width * videoSize.pixelWidthHeightRatio >= videoSize.height)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            lifecycleScope.launch { flushActivity(ended = true) }
            when {
                sleepEndOfScene -> {
                    sleepEndOfScene = false
                    toast("Sleep timer: stopped at the end of the scene")
                }
                queueMode && PlayQueue.autoNext && PlayQueue.hasNext -> nextScene()
            }
        }
    }

    private var orientationLocked = false

    private fun toggleOrientation() {
        orientationLocked = true
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ------------------------------------------------------------ sleep timer

    /** [minutes] null = off, -1 = end of this scene. */
    private fun setSleep(minutes: Int?) {
        player.volume = if (PlayerPrefs.muted) 0f else 1f
        when {
            minutes == null -> { sleepAt = null; sleepEndOfScene = false }
            minutes < 0 -> { sleepAt = null; sleepEndOfScene = true; toast("Sleep timer: stops at the end of this scene") }
            else -> { sleepEndOfScene = false; sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L; toast("Sleep timer: $minutes min") }
        }
    }

    private fun sleepLabel(): String? = when {
        sleepEndOfScene -> "end of scene"
        sleepAt != null -> formatDuration(sleepLeftMs / 1000.0)
        else -> null
    }

    // ------------------------------------------------------------ activity tracking

    private fun startTracker() {
        if (trackerJob != null) return
        trackerJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                val id = sceneId ?: continue
                if (player.isPlaying) {
                    playedMs += 1000
                    unsentPlayedMs += 1000
                }
                val settings = container.settings.value ?: continue
                if (!settings.trackActivity) continue
                if (!playCounted && playedMs >= settings.playCountAfterSeconds * 1000L) {
                    playCounted = true
                    runCatching { container.repository.addPlay(id) }
                }
                if (++tick % 15 == 0 && player.isPlaying) flushActivity()
            }
        }
    }

    private suspend fun flushActivity(ended: Boolean = false) {
        val id = sceneId ?: return
        val delta = unsentPlayedMs
        unsentPlayedMs = 0
        sendActivity(id, player.currentPosition, player.duration, delta, ended)
    }

    private suspend fun sendActivity(id: String, pos: Long, duration: Long, playedDeltaMs: Long, ended: Boolean) {
        val settings = container.settings.value ?: return
        if (!settings.trackActivity) return
        val dur = duration.takeIf { it != C.TIME_UNSET && it > 0 }
        // Near the end counts as finished: clear the resume point like the web UI.
        val resume = if (ended || (dur != null && pos > dur * 0.98)) 0.0 else pos / 1000.0
        runCatching { container.repository.saveActivity(id, resume, playedDeltaMs / 1000.0) }
    }

    // ------------------------------------------------------------ lifecycle / PiP

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters by itself (setAutoEnterEnabled); older versions need asking.
        if (Build.VERSION.SDK_INT < 31 && player.isPlaying) enterPip()
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipAction(cmd: Int, icon: Int, title: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, cmd,
            Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(AndroidIcon.createWithResource(this, icon), title, title, pi)
    }

    private fun pipParams(): PictureInPictureParams {
        val vs = player.videoSize
        val builder = PictureInPictureParams.Builder()
        if (vs.width > 0 && vs.height > 0) {
            val ratio = Rational(vs.width, vs.height)
            // Android rejects ratios outside ~2.39:1 .. 1:2.39
            if (ratio.toFloat() in 0.42f..2.39f) builder.setAspectRatio(ratio)
        }
        val playing = player.isPlaying
        val actions = mutableListOf(
            pipAction(PIP_BACK, R.drawable.ic_pip_rewind, "Back 5 seconds"),
            if (playing) pipAction(PIP_TOGGLE, R.drawable.ic_pip_pause, "Pause")
            else pipAction(PIP_TOGGLE, R.drawable.ic_pip_play, "Play"),
            pipAction(PIP_FORWARD, R.drawable.ic_pip_forward, "Forward 15 seconds"),
        )
        // In a queue, a "next scene" button too when there's room for it.
        if (queueMode && PlayQueue.hasNext) {
            val room = runCatching { maxNumPictureInPictureActions }.getOrDefault(3)
            if (room > actions.size) actions += pipAction(PIP_NEXT, R.drawable.ic_pip_next, "Next scene")
            else actions[2] = pipAction(PIP_NEXT, R.drawable.ic_pip_next, "Next scene")
        }
        builder.setActions(actions)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(playing)
        return builder.build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Leaving (not PiP) — pause and save where we are.
        if (!isInPictureInPictureMode) player.pause()
        lifecycleScope.launch { withContext(NonCancellable) { flushActivity() } }
    }

    override fun onDestroy() {
        trackerJob?.cancel()
        runCatching { unregisterReceiver(pipReceiver) }
        player.removeListener(listener)
        player.release()
        if (isFinishing && queueMode) PlayQueue.clear()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ UI

    @Composable
    private fun PlayerScreen() {
        val ui = rememberPlaybackUi(player)
        SideEffect { this.ui = ui }
        LaunchedEffect(frameMs) { ui.frameMs = frameMs }

        // Sleep timer countdown, fading the sound out over the last 10 seconds.
        LaunchedEffect(sleepAt) {
            val end = sleepAt ?: return@LaunchedEffect
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                sleepLeftMs = left.coerceAtLeast(0)
                val base = if (PlayerPrefs.muted) 0f else 1f
                if (left <= 0) {
                    player.pause()
                    player.volume = base
                    sleepAt = null
                    toast("Sleep timer: paused")
                    break
                }
                player.volume = if (left < 10_000) base * (left / 10_000f) else base
                delay(250)
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        player = this@PlayerActivity.player
                        // Our own Compose controls (PlayerControls.kt) replace ExoPlayer's, so taps
                        // and swipes all reach the gesture layer. Subtitles still draw here.
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (!inPip) {
                val queued = queueMode && PlayQueue.active
                PlayerOverlay(
                    player, ui, window, markers, scrub,
                    onPreviousScene = if (queued) ({ previousScene() }) else null,
                    onNextScene = if (queued && PlayQueue.hasNext) ({ nextScene() }) else null,
                ) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) { TopBar(ui) }
                }
            }
        }

        if (showUpNext) {
            PinControls(ui, true)
            UpNextSheet(
                onDismiss = { showUpNext = false },
                onJump = { i ->
                    showUpNext = false
                    PlayQueue.jumpTo(i)?.let { loadScene(it.id, null) }
                },
                onEndQueue = {
                    showUpNext = false
                    queueMode = false
                    PlayQueue.clear()
                },
            )
        }

        if (showSettings) {
            PinControls(ui, true)
            val text = groups(C.TRACK_TYPE_TEXT)
            val audio = groups(C.TRACK_TYPE_AUDIO)
            PlaybackSheet(
                speed = speed,
                onSpeed = { changeSpeed(it) },
                subtitles = text.mapIndexed { i, g -> PickOption(describe(g, i, C.TRACK_TYPE_TEXT), selected = g.isSelected) },
                onSubtitle = { selectText(it) },
                audio = audio.mapIndexed { i, g -> PickOption(describe(g, i, C.TRACK_TYPE_AUDIO), selected = g.isSelected) },
                onAudio = { selectAudio(it) },
                qualities = streams.mapIndexed { i, s -> PickOption(s.label ?: s.mimeType ?: "Stream ${i + 1}", selected = i == streamIndex) },
                onQuality = { i -> if (i != streamIndex) play(i, player.currentPosition) },
                sleepLabel = sleepLabel(),
                onSleep = { setSleep(it) },
                onRotate = { toggleOrientation(); showSettings = false },
                onPip = { showSettings = false; enterPip() },
                onDismiss = { showSettings = false },
            )
        }

        markerForm?.let { form ->
            Dialog(
                onDismissRequest = { markerForm = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                EditScreen(
                    form,
                    onSaved = {
                        markerForm = null
                        toast("Marker added")
                        refreshMarkers()
                        player.play()
                    },
                    onDeleted = { markerForm = null },
                    onClose = { markerForm = null; player.play() },
                )
            }
        }
    }

    private fun openMarkerForm() {
        val id = sceneId ?: return
        player.pause()
        markerForm = EditViewModel(
            container.repository, container.connection, EditKind.MARKER, null,
            sceneId = id, sceneTitle = title, startSeconds = player.currentPosition / 1000.0,
        )
    }

    private fun refreshMarkers() {
        val id = sceneId ?: return
        lifecycleScope.launch {
            runCatching { container.repository.scene(id) }.onSuccess { markers = it.markers.sortedBy { m -> m.seconds } }
        }
    }

    @Composable
    private fun TopBar(ui: PlaybackUi) {
        var markerMenu by remember { mutableStateOf(false) }
        PinControls(ui, markerMenu)
        val text = groups(C.TRACK_TYPE_TEXT)
        Row(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val sub = listOfNotNull(
                    if (queueMode && PlayQueue.active) "${PlayQueue.index + 1} of ${PlayQueue.entries.size}" else null,
                    streams.getOrNull(streamIndex)?.label,
                    if (speed != 1f) speedLabel(speed) else null,
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Ink.Amber, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }

            // Sleep timer running: its time left (tap to change it).
            sleepLabel()?.let { label ->
                TextButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Timer, "Sleep timer", tint = Ink.Amber)
                    Text(" $label", color = Ink.Amber, style = MaterialTheme.typography.labelMedium)
                }
            }

            if (queueMode && PlayQueue.active) {
                IconButton(onClick = { showUpNext = true }) { Icon(Icons.AutoMirrored.Filled.List, "Up next", tint = Color.White) }
            }

            // Subtitles: one tap toggles between off and the last-used (or first) track.
            if (text.isNotEmpty()) {
                val on = text.any { it.isSelected }
                IconButton(onClick = {
                    if (on) selectText(null) else {
                        val remembered = sceneId?.let { memory.get(it).subtitle }?.takeIf { it != "off" }
                        val idx = remembered?.let { k -> matchTrack(k, text.map { it.getTrackFormat(0).language to it.getTrackFormat(0).label }) } ?: 0
                        selectText(idx)
                    }
                    ui.poke()
                }) { Icon(Icons.Filled.ClosedCaption, if (on) "Subtitles off" else "Subtitles on", tint = if (on) Ink.Amber else Color.White) }
            }

            if (markers.isNotEmpty()) {
                Box {
                    IconButton(onClick = { markerMenu = true; ui.poke() }) { Icon(Icons.Filled.Bookmarks, "Markers", tint = Color.White) }
                    DropdownMenu(expanded = markerMenu, onDismissRequest = { markerMenu = false }) {
                        markers.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("${formatDuration(m.seconds)}  ${m.title.ifBlank { m.primaryTag?.name ?: "" }}") },
                                onClick = {
                                    markerMenu = false
                                    player.seekTo((m.seconds * 1000).toLong())
                                    player.play()
                                    ui.poke()
                                },
                            )
                        }
                    }
                }
            }

            if (sceneId != null && !container.repository.isOffline) {
                IconButton(onClick = { openMarkerForm() }) { Icon(Icons.Filled.BookmarkAdd, "Add marker here", tint = Color.White) }
            }
            IconButton(onClick = { showSettings = true; ui.poke() }) { Icon(Icons.Filled.MoreVert, "Playback settings", tint = Color.White) }
        }
    }
}
