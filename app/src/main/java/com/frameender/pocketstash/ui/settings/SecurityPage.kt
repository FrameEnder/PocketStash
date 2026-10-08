package com.frameender.pocketstash.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.frameender.pocketstash.security.AppLock
import com.frameender.pocketstash.security.Biometric
import com.frameender.pocketstash.security.LockPolicy
import com.frameender.pocketstash.security.NumberPad
import com.frameender.pocketstash.security.PinUnlock
import com.frameender.pocketstash.security.appLock
import com.frameender.pocketstash.security.findActivity
import com.frameender.pocketstash.ui.theme.Ink

/** What the page is doing after the current method has been confirmed. */
private enum class Next { OFF, PIN, BIOMETRIC, CHANGE_PIN }

@Composable
fun SecurityPage() {
    val context = LocalContext.current
    val lock = context.appLock
    val activity = remember(context) { context.findActivity() }
    val method by lock.method.collectAsState()
    val timeout by lock.timeoutSec.collectAsState()
    val blockShots by lock.blockScreenshots.collectAsState()
    val hideRecents by lock.hideRecents.collectAsState()
    val bio = remember { lock.biometricStatus() }

    var confirmPinFor by remember { mutableStateOf<Next?>(null) }
    var choosePin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** After the current method is confirmed (or there was none), do the change. */
    fun proceed(next: Next) {
        when (next) {
            Next.OFF -> {
                lock.turnOff()
                context.toast("App lock off")
            }
            Next.PIN, Next.CHANGE_PIN -> choosePin = true
            Next.BIOMETRIC -> {
                val a = activity ?: return
                Biometric.prompt(a, "Turn on fingerprint lock", "Confirm it's you") { ok, err ->
                    if (ok) {
                        lock.useBiometric()
                        context.toast("Fingerprint lock on")
                    } else if (err != null) {
                        context.toast(err)
                    }
                }
            }
        }
    }

    /** Changing or turning off the lock first asks for the current one. */
    fun change(next: Next) {
        when (method) {
            LockPolicy.METHOD_PIN -> confirmPinFor = next
            LockPolicy.METHOD_BIOMETRIC -> {
                val a = activity ?: return
                Biometric.prompt(a, "Confirm it's you", "Needed to change the app lock") { ok, err ->
                    if (ok) proceed(next) else if (err != null) context.toast(err)
                }
            }
            else -> proceed(next)
        }
    }

    SettingsPage("Privacy & security") {
        GroupLabel("App lock")
        SettingsCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Lock PocketStash with", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                SegmentedSetting(
                    listOf(
                        Triple(LockPolicy.METHOD_NONE, "Off", Icons.Filled.LockOpen),
                        Triple(LockPolicy.METHOD_PIN, "Passcode", Icons.Filled.Dialpad),
                        Triple(LockPolicy.METHOD_BIOMETRIC, "Fingerprint", Icons.Filled.Fingerprint),
                    ),
                    method,
                ) { pick ->
                    if (pick == method) return@SegmentedSetting
                    when (pick) {
                        LockPolicy.METHOD_NONE -> change(Next.OFF)
                        LockPolicy.METHOD_PIN -> change(Next.PIN)
                        LockPolicy.METHOD_BIOMETRIC ->
                            if (!bio.available) context.toast(bio.note ?: "Fingerprint isn't available") else change(Next.BIOMETRIC)
                    }
                }
                val note = when (method) {
                    LockPolicy.METHOD_PIN -> "A passcode of ${LockPolicy.MIN_PIN}–${LockPolicy.MAX_PIN} digits, only for this app."
                    LockPolicy.METHOD_BIOMETRIC -> bio.note ?: "Your fingerprint, with your phone's screen lock as a fallback."
                    else -> "Anyone holding your unlocked phone can open PocketStash."
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(top = 8.dp))
            }
            if (method == LockPolicy.METHOD_PIN) {
                CardDivider()
                ActionRow("Change passcode", "Asks for the current one first") { change(Next.CHANGE_PIN) }
            }
            CardDivider()
            ChipsSetting(
                "Lock after leaving",
                AppLock.TIMEOUTS,
                timeout,
                summary = if (method == LockPolicy.METHOD_NONE) "Applies once a lock is set" else "How long PocketStash can be in the background before it asks again",
            ) { v -> lock.setTimeout(v) }
        }
        Hint(
            "Asked whenever PocketStash opens or comes back from the background: switching apps, going home, or the " +
                "screen turning off. Playback pauses while it's locked.",
        )

        GroupLabel("Screen")
        SettingsCard {
            SwitchSetting(
                "Hide preview in recent apps",
                if (Build.VERSION.SDK_INT >= 33) "The recent-apps screen shows a blank card instead of what was open"
                else "The recent-apps screen shows a blank card (on this Android version this also blocks screenshots)",
                hideRecents,
            ) { v -> lock.setHideRecents(v) }
            CardDivider()
            SwitchSetting(
                "Block screenshots",
                "Screenshots and screen recordings of PocketStash come out black",
                blockShots,
            ) { v -> lock.setBlockScreenshots(v) }
        }
    }

    // ---------------------------------------------------------------- dialogs

    confirmPinFor?.let { next ->
        PinDialog(onDismiss = { confirmPinFor = null }) {
            Text("Enter your current passcode", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
            Spacer(Modifier.height(4.dp))
            PinUnlock(
                onUnlocked = {
                    confirmPinFor = null
                    proceed(next)
                },
                showForgot = false,
            )
        }
    }

    if (choosePin) {
        ChoosePinDialog(
            onDismiss = { choosePin = false },
            onChosen = { pin ->
                choosePin = false
                scope.launch {
                    withContext(Dispatchers.Default) { lock.setPin(pin) }
                    context.toast("Passcode set")
                }
            },
        )
    }
}

/** A full-height dialog holding a number pad. */
@Composable
private fun PinDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Ink.Raised,
            contentColor = Ink.Text,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

/** Type a new passcode twice. */
@Composable
private fun ChoosePinDialog(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    PinDialog(onDismiss) {
        Text(
            if (first == null) "Choose a passcode" else "Enter it again",
            style = MaterialTheme.typography.titleMedium, color = Ink.Text,
        )
        Text(
            if (first == null) "${LockPolicy.MIN_PIN}–${LockPolicy.MAX_PIN} digits" else "To make sure it's right",
            style = MaterialTheme.typography.bodySmall, color = Ink.Muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pin.isEmpty()) Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.Line))
            repeat(pin.length) { Box(Modifier.size(12.dp).clip(CircleShape).background(Ink.Amber)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(error ?: " ", color = Ink.Red, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        NumberPad(
            enabled = true,
            canSubmit = LockPolicy.isValidPin(pin),
            onDigit = { d -> if (pin.length < LockPolicy.MAX_PIN) { pin += d; error = null } },
            onBackspace = { pin = pin.dropLast(1) },
            onSubmit = {
                val f = first
                when {
                    f == null -> { first = pin; pin = "" }
                    f == pin -> onChosen(pin)
                    else -> { first = null; pin = ""; error = "Those didn't match. Start again." }
                }
            },
        )
        Spacer(Modifier.fillMaxWidth().height(0.dp))
    }
}
