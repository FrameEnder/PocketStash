package com.frameender.pocketstash.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.formatDuration
import com.frameender.pocketstash.data.model.Marker
import com.frameender.pocketstash.data.model.StreamEndpoint
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.PocketStashTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen ExoPlayer for Stash scenes (or a direct clip URL).
 * - direct stream first, falls back through Stash's transcode endpoints on error
 * - resume point, play count and watch time synced back to Stash
 * - markers as chapters, playback speed, PiP, auto orientation
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SCENE = "scene_id"
        private const val EXTRA_START = "start_seconds"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun sceneIntent(context: Context, sceneId: String, startSeconds: Double?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_SCENE, sceneId)
                .apply { if (startSeconds != null) putExtra(EXTRA_START, startSeconds) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun urlIntent(context: Context, url: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private lateinit var player: ExoPlayer
    private var sceneId: String? = null

    // UI state
    private var title by mutableStateOf("")
    private var streams by mutableStateOf<List<StreamEndpoint>>(emptyList())
    private var streamIndex by mutableStateOf(0)
    private var markers by mutableStateOf<List<Marker>>(emptyList())
    private var controlsVisible by mutableStateOf(true)
    private var inPip by mutableStateOf(false)
    private var speed by mutableStateOf(1f)

    // Activity tracking
    private var playedMs = 0L
    private var unsentPlayedMs = 0L
    private var playCounted = false
    private var trackerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val conn = container.connection
        val dataSource = OkHttpDataSource.Factory(conn.http)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(listener)

        setContent {
            PocketStashTheme {
                PlayerScreen()
            }
        }

        val id = intent.getStringExtra(EXTRA_SCENE)
        val url = intent.getStringExtra(EXTRA_URL)
        when {
            id != null -> loadScene(id, if (intent.hasExtra(EXTRA_START)) intent.getDoubleExtra(EXTRA_START, 0.0) else null)
            url != null -> {
                title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
            }
            else -> finish()
        }
    }

    private fun loadScene(id: String, start: Double?) {
        sceneId = id
        lifecycleScope.launch {
            try {
                val scene = container.repository.scene(id)
                val conn = container.connection
                title = scene.displayTitle
                markers = scene.markers.sortedBy { it.seconds }

                // Direct file first, then whatever Stash offers (HLS/DASH/MP4/WebM transcodes).
                val direct = scene.paths.stream?.let { StreamEndpoint(it, null, "Direct") }
                streams = (listOfNotNull(direct) + scene.streams)
                    .distinctBy { it.url }
                    .map { it.copy(url = conn.media(it.url) ?: it.url) }
                if (streams.isEmpty()) {
                    toast("Stash returned no playable streams")
                    finish(); return@launch
                }

                val settings = container.settings.value
                val startAt = start ?: if (settings?.resumePlayback != false) scene.resumeTime else null
                val duration = scene.duration
                val resumeMs = startAt
                    ?.takeIf { it > 0 && (duration == null || it < duration - 5) }
                    ?.let { (it * 1000).toLong() } ?: 0L
                play(0, resumeMs)
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't load scene")
                finish()
            }
        }
    }

    private fun play(index: Int, positionMs: Long) {
        streamIndex = index
        val s = streams[index]
        val builder = MediaItem.Builder().setUri(s.url)
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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            if (orientationLocked) return
            requestedOrientation = if (videoSize.width * videoSize.pixelWidthHeightRatio >= videoSize.height)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            updatePipParams()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) lifecycleScope.launch { flushActivity(ended = true) }
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

    // ------------------------------------------------------------ activity tracking

    private fun startTracker() {
        if (sceneId == null || trackerJob != null) return
        trackerJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                if (player.isPlaying) {
                    playedMs += 1000
                    unsentPlayedMs += 1000
                }
                val settings = container.settings.value ?: continue
                if (!settings.trackActivity) continue
                if (!playCounted && playedMs >= settings.playCountAfterSeconds * 1000L) {
                    playCounted = true
                    runCatching { container.repository.addPlay(sceneId!!) }
                }
                if (++tick % 15 == 0 && player.isPlaying) flushActivity()
            }
        }
    }

    private suspend fun flushActivity(ended: Boolean = false) {
        val id = sceneId ?: return
        val settings = container.settings.value ?: return
        if (!settings.trackActivity) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pos = player.currentPosition
        // Near the end counts as finished: clear the resume point like the web UI.
        val resume = if (ended || (duration != null && pos > duration * 0.98)) 0.0 else pos / 1000.0
        val delta = unsentPlayedMs / 1000.0
        unsentPlayedMs = 0
        runCatching { container.repository.saveActivity(id, resume, delta) }
    }

    // ------------------------------------------------------------ lifecycle / PiP

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val vs = player.videoSize
        val builder = PictureInPictureParams.Builder()
        if (vs.width > 0 && vs.height > 0) {
            val ratio = Rational(vs.width, vs.height)
            // Android rejects ratios outside ~2.39:1 .. 1:2.39
            if (ratio.toFloat() in 0.42f..2.39f) builder.setAspectRatio(ratio)
        }
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
        player.removeListener(listener)
        player.release()
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
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        player = this@PlayerActivity.player
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowSubtitleButton(true)
                        controllerShowTimeoutMs = 3500
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                            controlsVisible = v == android.view.View.VISIBLE
                        })
                    }
                },
                update = { it.useController = !inPip },
                modifier = Modifier.fillMaxSize(),
            )

            AnimatedVisibility(
                controlsVisible && !inPip, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                TopBar()
            }
        }
    }

    @Composable
    private fun TopBar() {
        var streamMenu by remember { mutableStateOf(false) }
        var markerMenu by remember { mutableStateOf(false) }
        var speedMenu by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                streams.getOrNull(streamIndex)?.label?.let {
                    Text(it, color = Ink.Amber, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (markers.isNotEmpty()) {
                Box {
                    IconButton(onClick = { markerMenu = true }) { Icon(Icons.Filled.Bookmarks, "Markers", tint = Color.White) }
                    DropdownMenu(expanded = markerMenu, onDismissRequest = { markerMenu = false }) {
                        markers.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("${formatDuration(m.seconds)}  ${m.title.ifBlank { m.primaryTag?.name ?: "" }}") },
                                onClick = {
                                    markerMenu = false
                                    player.seekTo((m.seconds * 1000).toLong())
                                    player.play()
                                },
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { speedMenu = true }) { Icon(Icons.Filled.Speed, "Speed", tint = Color.White) }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                        DropdownMenuItem(
                            text = { Text("${sp}×", color = if (sp == speed) Ink.Amber else Ink.Text) },
                            onClick = { speedMenu = false; speed = sp; player.setPlaybackSpeed(sp) },
                        )
                    }
                }
            }

            if (streams.size > 1) {
                Box {
                    IconButton(onClick = { streamMenu = true }) { Icon(Icons.Filled.HighQuality, "Stream", tint = Color.White) }
                    DropdownMenu(expanded = streamMenu, onDismissRequest = { streamMenu = false }) {
                        streams.forEachIndexed { i, s ->
                            DropdownMenuItem(
                                text = { Text(s.label ?: s.mimeType ?: "Stream ${i + 1}", color = if (i == streamIndex) Ink.Amber else Ink.Text) },
                                onClick = { streamMenu = false; play(i, player.currentPosition) },
                            )
                        }
                    }
                }
            }

            IconButton(onClick = { toggleOrientation() }) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
            IconButton(onClick = { enterPip() }) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
        }
    }
}
