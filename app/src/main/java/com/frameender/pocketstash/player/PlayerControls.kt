package com.frameender.pocketstash.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.frameender.pocketstash.data.formatDuration
import com.frameender.pocketstash.data.model.Marker
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Double-tap on the left half skips back this far. */
const val SKIP_BACK_MS = 5_000L

/** Double-tap on the right half skips forward this far. */
const val SKIP_FORWARD_MS = 15_000L

/** Two taps closer together than this count as a double tap. */
private const val DOUBLE_TAP_MS = 280L

/** After a double-tap seek, each further tap within this window seeks again. */
private const val TAP_CHAIN_MS = 800L

/** Controls hide themselves after this long while the video plays. */
private const val CONTROLS_HIDE_MS = 3_000L

/** Swipes that start this close to the top edge are left to the system (notification shade). */
private const val TOP_DEAD_ZONE = 0.08f

/** Mute state for this app session, so unmuting once keeps sound on for the next scene. */
object PlayerPrefs {
    var muted by mutableStateOf(false)
}

/** What the controls need to know about the player, refreshed from its events and a 4 Hz poll. */
@Stable
class PlaybackUi {
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)

    /** Controls (top bar, play button, seek bar) are showing. */
    var controls by mutableStateOf(true)

    /** Bumped on every interaction so the auto-hide timer restarts. */
    var touches by mutableIntStateOf(0)

    fun poke() { touches++ }
}

@Composable
fun rememberPlaybackUi(player: ExoPlayer): PlaybackUi {
    val ui = remember(player) { PlaybackUi() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                ui.playing = p.isPlaying
                ui.buffering = p.playbackState == Player.STATE_BUFFERING
                ui.ended = p.playbackState == Player.STATE_ENDED
                ui.position = p.currentPosition
                ui.duration = p.duration.coerceAtLeast(0L)
                ui.buffered = p.bufferedPosition
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            ui.position = player.currentPosition
            ui.duration = player.duration.coerceAtLeast(0L)
            ui.buffered = player.bufferedPosition
            delay(250)
        }
    }
    // Auto-hide while playing; any touch restarts the countdown.
    LaunchedEffect(ui.controls, ui.playing, ui.touches) {
        if (ui.controls && ui.playing) {
            delay(CONTROLS_HIDE_MS)
            ui.controls = false
        }
    }
    LaunchedEffect(PlayerPrefs.muted) { player.volume = if (PlayerPrefs.muted) 0f else 1f }
    return ui
}

fun togglePlay(player: ExoPlayer, ui: PlaybackUi) {
    when {
        ui.ended -> { player.seekTo(0); player.play() }
        player.isPlaying -> player.pause()
        else -> player.play()
    }
    ui.poke()
}

private enum class AdjustKind { BRIGHTNESS, VOLUME }

/**
 * Everything drawn over the video: the gesture layer (double-tap seek, brightness and
 * volume swipes, tap to show/hide), the seek feedback, the side sliders, and the controls.
 * [topBar] is the activity's title/actions bar; it fades with the rest of the controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerOverlay(
    player: ExoPlayer,
    ui: PlaybackUi,
    window: Window,
    markers: List<Marker>,
    topBar: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    // Double-tap seek feedback: which side (-1 left, 1 right, 0 none) and how far in total.
    var seekSide by remember { mutableIntStateOf(0) }
    var seekTotalMs by remember { mutableLongStateOf(0L) }
    // Brightness / volume swipe feedback.
    var adjustKind by remember { mutableStateOf<AdjustKind?>(null) }
    var adjustLevel by remember { mutableFloatStateOf(0f) }

    fun brightnessNow(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }
    fun setBrightness(v: Float) {
        // Only the player window changes; the phone's brightness returns when you leave.
        window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
    }
    fun volumeNow(): Float =
        if (PlayerPrefs.muted) 0f else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
    fun setVolume(v: Float): Float {
        val steps = (v * maxVolume).roundToInt().coerceIn(0, maxVolume)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0) }
        // Turning the volume up while muted means you want sound.
        if (steps > 0 && PlayerPrefs.muted) PlayerPrefs.muted = false
        return steps.toFloat() / maxVolume
    }

    Box(Modifier.fillMaxSize()) {
        // ---------- Gesture layer ----------
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    var lastTapUp = 0L
                    var chainUntil = 0L
                    var pendingTap: Job? = null
                    var seekHide: Job? = null
                    var adjustHide: Job? = null

                    fun seek(side: Int) {
                        val delta = if (side < 0) -SKIP_BACK_MS else SKIP_FORWARD_MS
                        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
                        ui.position = player.currentPosition
                        if (seekSide != side) seekTotalMs = 0L
                        seekSide = side
                        seekTotalMs += abs(delta)
                        seekHide?.cancel()
                        seekHide = scope.launch {
                            delay(TAP_CHAIN_MS + 250)
                            seekSide = 0
                            seekTotalMs = 0L
                        }
                        ui.poke()
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val leftHalf = down.position.x < size.width / 2f
                        val inTopDeadZone = down.position.y < size.height * TOP_DEAD_ZONE
                        val slop = viewConfiguration.touchSlop
                        var dragging = false
                        var startLevel = 0f
                        var upX = down.position.x
                        var upTime = down.uptimeMillis
                        var movedSideways = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val d = change.position - down.position
                            if (change.changedToUpIgnoreConsumed()) {
                                upX = change.position.x
                                upTime = change.uptimeMillis
                                break
                            }
                            if (!dragging && abs(d.x) > slop * 2 && abs(d.x) > abs(d.y)) movedSideways = true
                            if (!dragging && !movedSideways && !inTopDeadZone && abs(d.y) > slop && abs(d.y) > abs(d.x)) {
                                dragging = true
                                pendingTap?.cancel()
                                startLevel = if (leftHalf) brightnessNow() else volumeNow()
                                adjustHide?.cancel()
                            }
                            if (dragging) {
                                // Up is more; a swipe across ~3/4 of the screen height covers the full range.
                                val target = (startLevel - d.y / (size.height * 0.75f)).coerceIn(0f, 1f)
                                if (leftHalf) {
                                    setBrightness(target)
                                    adjustKind = AdjustKind.BRIGHTNESS
                                    adjustLevel = target
                                } else {
                                    adjustLevel = setVolume(target)
                                    adjustKind = AdjustKind.VOLUME
                                }
                                change.consume()
                            }
                        }

                        if (dragging) {
                            adjustHide = scope.launch { delay(800); adjustKind = null }
                            return@awaitEachGesture
                        }
                        if (movedSideways) return@awaitEachGesture

                        val side = if (upX < size.width / 2f) -1 else 1
                        when {
                            // Already seeking: every tap keeps going (and switches direction if needed).
                            upTime < chainUntil -> {
                                seek(side)
                                chainUntil = upTime + TAP_CHAIN_MS
                            }
                            // Second tap of a double tap.
                            upTime - lastTapUp < DOUBLE_TAP_MS && pendingTap?.isActive == true -> {
                                pendingTap?.cancel()
                                seek(side)
                                chainUntil = upTime + TAP_CHAIN_MS
                                lastTapUp = 0L
                            }
                            // Maybe a single tap: wait to see if a second one follows.
                            else -> {
                                lastTapUp = upTime
                                pendingTap = scope.launch {
                                    delay(DOUBLE_TAP_MS)
                                    ui.controls = !ui.controls
                                    ui.poke()
                                }
                            }
                        }
                    }
                },
        )

        // ---------- Double-tap seek feedback ----------
        if (seekSide != 0) {
            SeekBubble(
                forward = seekSide > 0,
                totalMs = seekTotalMs,
                modifier = Modifier.align(if (seekSide > 0) Alignment.CenterEnd else Alignment.CenterStart),
            )
        }

        // ---------- Brightness (left) / volume (right) slider ----------
        adjustKind?.let { kind ->
            SideSlider(
                icon = when {
                    kind == AdjustKind.BRIGHTNESS -> Icons.Filled.BrightnessMedium
                    adjustLevel <= 0f || PlayerPrefs.muted -> Icons.AutoMirrored.Filled.VolumeOff
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                level = adjustLevel,
                modifier = Modifier
                    .align(if (kind == AdjustKind.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd)
                    .safeDrawingPadding()
                    .padding(horizontal = 28.dp),
            )
        }

        if (ui.buffering && !ui.controls) {
            CircularProgressIndicator(
                color = Ink.Amber, strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(48.dp),
            )
        }

        // ---------- Controls ----------
        AnimatedVisibility(
            visible = ui.controls || !ui.playing,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Dim top and bottom so white controls read on bright video.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                0.25f to Color.Transparent,
                                0.7f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.65f),
                            ),
                        ),
                )
                topBar()
                CenterButtons(player, ui, Modifier.align(Alignment.Center))
                SeekBar(
                    player, ui, markers,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CenterButtons(player: ExoPlayer, ui: PlaybackUi, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        RoundButton(Icons.Filled.Replay5, "Back 5 seconds", 52) {
            player.seekTo((player.currentPosition - SKIP_BACK_MS).coerceAtLeast(0L)); ui.poke()
        }
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Ink.Amber),
            contentAlignment = Alignment.Center,
        ) {
            if (ui.buffering) {
                CircularProgressIndicator(color = Ink.OnAmber, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
            } else {
                IconButton(onClick = { togglePlay(player, ui) }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        when {
                            ui.ended -> Icons.Filled.Replay
                            ui.playing -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        if (ui.playing) "Pause" else "Play",
                        tint = Ink.OnAmber,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
        RoundButton(Icons.Filled.Replay, "Forward 15 seconds", 52, label = "15") {
            val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + SKIP_FORWARD_MS).coerceAtMost(dur)); ui.poke()
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, sizeDp: Int, label: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(sizeDp.dp)) {
            if (label == null) {
                Icon(icon, description, tint = Color.White, modifier = Modifier.size((sizeDp * 0.6f).dp))
            } else {
                // No stock "forward 15" icon: draw the arrow and put the number in the middle.
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Replay, description, tint = Color.White,
                        modifier = Modifier.size((sizeDp * 0.62f).dp).graphicsFlipX())
                    Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** Mirror horizontally (turns the "replay" arrow into a "forward" arrow). */
private fun Modifier.graphicsFlipX(): Modifier = this.graphicsLayer { scaleX = -1f }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(player: ExoPlayer, ui: PlaybackUi, markers: List<Marker>, modifier: Modifier = Modifier) {
    // While dragging, the thumb follows the finger; the player seeks on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = ui.duration
    val fraction = dragging ?: if (duration > 0) (ui.position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragging != null) (dragging!! * duration).toLong() else ui.position

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            // Buffered range and marker ticks, drawn under the slider's own track.
            if (duration > 0) {
                Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 10.dp)) {
                    val buffered = (ui.buffered.toFloat() / duration).coerceIn(0f, 1f)
                    drawRect(Color.White.copy(alpha = 0.18f), size = size.copy(width = size.width * buffered))
                }
            }
            Slider(
                value = fraction,
                onValueChange = { dragging = it; ui.poke() },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                    ui.poke()
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Ink.Amber,
                    activeTrackColor = Ink.Amber,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (duration > 0 && markers.isNotEmpty()) {
                Canvas(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 10.dp)) {
                    markers.forEach { m ->
                        val x = ((m.seconds * 1000).toFloat() / duration).coerceIn(0f, 1f) * size.width
                        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDuration(shownMs / 1000.0) + "  /  " + formatDuration(duration / 1000.0),
                color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                modifier = Modifier.padding(start = 4.dp),
            )
            // Name of the marker the playhead is in, if any.
            val current = markers.lastOrNull { it.seconds * 1000 <= shownMs && (it.endSeconds == null || it.endSeconds * 1000 >= shownMs) }
            if (current != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    current.title.ifBlank { current.primaryTag?.name ?: "" },
                    color = Ink.Amber, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            MuteButton(ui)
        }
    }
}

@Composable
fun MuteButton(ui: PlaybackUi? = null) {
    val muted = PlayerPrefs.muted
    IconButton(onClick = { PlayerPrefs.muted = !muted; ui?.poke() }) {
        Icon(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            if (muted) "Unmute" else "Mute",
            tint = if (muted) Ink.Amber else Color.White,
        )
    }
}

/** The "« −10s" ripple on whichever side is being double-tapped. */
@Composable
private fun SeekBubble(forward: Boolean, totalMs: Long, modifier: Modifier = Modifier) {
    val shape = if (forward) {
        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    } else {
        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    }
    Box(
        modifier
            .fillMaxHeight(0.7f)
            .width(150.dp)
            .background(Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                null, tint = Color.White, modifier = Modifier.size(36.dp),
            )
            Text(
                (if (forward) "+" else "−") + "${totalMs / 1000}s",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Vertical level bar shown on the side being swiped (brightness left, volume right). */
@Composable
private fun SideSlider(icon: ImageVector, level: Float, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(level * 100).roundToInt()}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(level.coerceIn(0f, 1f)).background(Ink.Amber))
        }
        Spacer(Modifier.height(10.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
