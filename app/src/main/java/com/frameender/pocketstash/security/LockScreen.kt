package com.frameender.pocketstash.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Wraps a screen: while the app is locked, the lock screen covers it completely (the screen
 * underneath keeps its place, so unlocking returns exactly where you were).
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lock = context.appLock
    val locked by lock.locked.collectAsState()
    Box(Modifier.fillMaxSize()) {
        // Hidden from screen readers too while locked.
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { content() }
        if (locked) {
            // A plain cover in the screen itself too, so nothing shows around the window below
            // (status bar area, the moment before it appears).
            Box(
                Modifier.fillMaxSize().background(Ink.Bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            )
            // Its own full-screen window, so it also covers any dialog that was open when the
            // app was left (a plain overlay would sit underneath dialog windows).
            Dialog(
                onDismissRequest = { context.findActivity()?.moveTaskToBack(true) },
                properties = DialogProperties(
                    dismissOnBackPress = true, // handled above: Back leaves the app
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) { LockScreen() }
        }
    }
}

@Composable
fun LockScreen() {
    val context = LocalContext.current
    val lock = context.appLock
    val activity = remember(context) { context.findActivity() }
    val method by lock.method.collectAsState()

    // Back leaves the app instead of slipping past the lock.
    BackHandler { activity?.moveTaskToBack(true) }

    // Light text on the dark backdrop (a bare Box doesn't set a content color, which left the
    // text and digits dark).
    CompositionLocalProvider(LocalContentColor provides Ink.Text) { LockScreenBody(method, activity) }
}

@Composable
private fun LockScreenBody(method: String, activity: Activity?) {
    val lock = LocalContext.current.appLock
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Bg)
            // Swallow every touch so nothing underneath can be used.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Ink.Amber.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Lock, null, tint = Ink.Amber, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.height(14.dp))
            Text("PocketStash is locked", style = MaterialTheme.typography.titleLarge, color = Ink.Text)
            Spacer(Modifier.height(4.dp))
            when (method) {
                LockPolicy.METHOD_PIN -> PinUnlock(onUnlocked = { lock.unlock() })
                LockPolicy.METHOD_BIOMETRIC -> BiometricUnlock(activity)
                else -> LaunchedEffect(Unit) { lock.unlock() }
            }
        }
    }
}

@Composable
private fun BiometricUnlock(activity: Activity?) {
    val lock = LocalContext.current.appLock
    var error by remember { mutableStateOf<String?>(null) }
    // Ask once by itself each time the app comes back; after a cancel, the button asks again.
    var asked by remember { mutableStateOf(false) }

    fun ask() {
        val a = activity ?: return
        error = null
        Biometric.prompt(a, "Unlock PocketStash", "Confirm it's you") { ok, err ->
            if (ok) lock.unlock() else error = err
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { asked = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!asked) {
            asked = true
            ask()
        }
    }

    Text("Use your fingerprint or your phone's screen lock", color = Ink.Muted, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { ask() },
        colors = ButtonDefaults.buttonColors(containerColor = Ink.Amber, contentColor = Ink.OnAmber),
    ) {
        Icon(Icons.Filled.Fingerprint, null)
        Spacer(Modifier.width(8.dp))
        Text("Unlock")
    }
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = Ink.Red, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/**
 * Passcode entry with its own number pad (the passcode never goes through a keyboard app).
 * Used by the lock screen and by Settings to confirm changes.
 */
@Composable
fun PinUnlock(onUnlocked: () -> Unit, showForgot: Boolean = true) {
    val context = LocalContext.current
    val lock = context.appLock
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var waitMs by remember { mutableLongStateOf(lock.waitRemainingMs()) }
    val shake = remember { Animatable(0f) }

    // Count down a forced wait after too many wrong guesses.
    LaunchedEffect(waitMs > 0) {
        while (waitMs > 0) {
            delay(250)
            waitMs = lock.waitRemainingMs()
        }
    }

    var checking by remember { mutableStateOf(false) }

    fun onResult(ok: Boolean) {
        if (ok) {
            pin = ""
            error = null
            onUnlocked()
        } else {
            pin = ""
            waitMs = lock.waitRemainingMs()
            val left = LockPolicy.FREE_TRIES - lock.failedTries()
            error = when {
                waitMs > 0 -> null
                left in 1..2 -> "Wrong passcode · $left ${if (left == 1) "try" else "tries"} before a wait"
                else -> "Wrong passcode"
            }
            scope.launch {
                for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x, tween(45))
            }
        }
    }


    fun submit() {
        if (pin.length < LockPolicy.MIN_PIN || waitMs > 0 || checking) return
        val attempt = pin
        checking = true
        scope.launch {
            // Hashing is deliberately slow (to resist guessing), so it runs off the main thread.
            val ok = withContext(Dispatchers.Default) { lock.checkPin(attempt) }
            checking = false
            onResult(ok)
        }
    }

    Text(
        if (waitMs > 0) "Too many tries. Try again in ${formatWait(waitMs)}" else "Enter your passcode",
        color = if (waitMs > 0) Ink.Red else Ink.Muted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    // Dots, one per digit typed (the length isn't fixed, so there are no empty slots to give it away).
    Row(
        Modifier.height(16.dp).offset { IntOffset(shake.value.roundToInt(), 0) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pin.isEmpty()) Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.Line))
        repeat(pin.length) { Box(Modifier.size(12.dp).clip(CircleShape).background(Ink.Amber)) }
    }
    Spacer(Modifier.height(8.dp))
    Text(error ?: " ", color = Ink.Red, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    NumberPad(
        enabled = waitMs <= 0 && !checking,
        canSubmit = pin.length >= LockPolicy.MIN_PIN,
        onDigit = { d -> if (pin.length < LockPolicy.MAX_PIN) { pin += d; error = null } },
        onBackspace = { pin = pin.dropLast(1) },
        onSubmit = { submit() },
    )
    if (showForgot && Biometric.canAskDeviceCredential && lock.deviceSecure() && activity != null) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            Biometric.prompt(activity, "Forgot your passcode?", "Confirm with your phone's screen lock", credentialOnly = true) { ok, err ->
                if (ok) {
                    // Proven owner: the old passcode is removed and the app opens.
                    lock.turnOff()
                    Toast.makeText(context, "Passcode removed. Set a new one in Settings → Privacy & security.", Toast.LENGTH_LONG).show()
                    onUnlocked()
                } else if (err != null) {
                    error = err
                }
            }
        }) { Text("Forgot passcode? Use your phone's screen lock", color = Ink.Muted, textAlign = TextAlign.Center) }
    }
}

private fun formatWait(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** 1–9, then backspace · 0 · enter. */
@Composable
fun NumberPad(
    enabled: Boolean,
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d -> PadKey(enabled, onClick = { onDigit(d) }) { Text(d.toString(), fontSize = 26.sp, color = Ink.Text) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PadKey(enabled, subtle = true, onClick = onBackspace) {
                Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = Ink.Muted)
            }
            PadKey(enabled, onClick = { onDigit('0') }) { Text("0", fontSize = 26.sp, color = Ink.Text) }
            PadKey(enabled && canSubmit, accent = true, onClick = onSubmit) {
                Icon(Icons.Filled.Check, "Enter", tint = if (enabled && canSubmit) Ink.OnAmber else Ink.Muted)
            }
        }
    }
}

@Composable
private fun PadKey(
    enabled: Boolean,
    subtle: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bg = when {
        accent && enabled -> Ink.Amber
        subtle -> Color.Transparent
        else -> Ink.Surface
    }
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
