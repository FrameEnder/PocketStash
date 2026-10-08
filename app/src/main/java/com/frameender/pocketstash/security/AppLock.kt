package com.frameender.pocketstash.security

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import com.frameender.pocketstash.PocketStashApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.WeakHashMap

/**
 * Privacy & security: the app lock (a passcode or the phone's fingerprint, asked when the app
 * is opened or comes back from the background), hiding the app's preview in recent apps, and
 * blocking screenshots.
 *
 * Settings live in their own small preferences file, apart from the rest of the settings, so
 * "reset to default" elsewhere never turns the lock off. The passcode is stored only as a
 * salted PBKDF2 hash ([LockPolicy]).
 */
class AppLock(private val app: Application) : Application.ActivityLifecycleCallbacks {

    private val prefs = app.getSharedPreferences("app_lock", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ settings

    private val _method = MutableStateFlow(prefs.getString(K_METHOD, LockPolicy.METHOD_NONE) ?: LockPolicy.METHOD_NONE)
    /** "none", "pin" or "biometric". */
    val method: StateFlow<String> = _method

    private val _timeoutSec = MutableStateFlow(prefs.getInt(K_TIMEOUT, 0))
    /** Lock after this long in the background (0 = immediately). */
    val timeoutSec: StateFlow<Int> = _timeoutSec

    private val _blockScreenshots = MutableStateFlow(prefs.getBoolean(K_SECURE, false))
    val blockScreenshots: StateFlow<Boolean> = _blockScreenshots

    private val _hideRecents = MutableStateFlow(prefs.getBoolean(K_HIDE_RECENTS, true))
    /** Blank preview in the recent-apps screen instead of a snapshot of what was open. */
    val hideRecents: StateFlow<Boolean> = _hideRecents

    val enabled: Boolean get() = _method.value != LockPolicy.METHOD_NONE

    // ------------------------------------------------------------------ lock state

    /** True while the lock screen must cover the app. Starts locked when a lock is set. */
    private val _locked = MutableStateFlow(enabled)
    val locked: StateFlow<Boolean> = _locked

    private var started = 0
    private var inBackground = true
    private var leftAt = 0L
    /** Set when the app itself opens something it expects to come back from. */
    private var skipUntil = 0L
    private val live = WeakHashMap<Activity, Unit>()

    init {
        app.registerActivityLifecycleCallbacks(this)
    }

    fun unlock() {
        _locked.value = false
        prefs.edit().putInt(K_FAILS, 0).putLong(K_WAIT_UNTIL, 0).apply()
    }

    /**
     * The app is about to open something of its own (a file picker, the install permission
     * page, the phone's lock screen) and will come straight back: don't ask again for that.
     */
    fun expectReturn() {
        skipUntil = SystemClock.elapsedRealtime() + 2 * 60_000L
    }

    // ------------------------------------------------------------------ passcode

    fun hasPin(): Boolean = !prefs.getString(K_PIN, null).isNullOrBlank()

    /** Checks a passcode, counting wrong guesses. Returns false while a wait is in force. */
    fun checkPin(pin: String): Boolean {
        if (waitRemainingMs() > 0) return false
        val ok = LockPolicy.verifyPin(pin, prefs.getString(K_PIN, null))
        if (ok) {
            prefs.edit().putInt(K_FAILS, 0).putLong(K_WAIT_UNTIL, 0).apply()
        } else {
            val fails = prefs.getInt(K_FAILS, 0) + 1
            val wait = LockPolicy.waitAfter(fails)
            prefs.edit()
                .putInt(K_FAILS, fails)
                // Wall clock, so the wait survives the app being closed.
                .putLong(K_WAIT_UNTIL, if (wait > 0) System.currentTimeMillis() + wait else 0)
                .apply()
        }
        return ok
    }

    fun failedTries(): Int = prefs.getInt(K_FAILS, 0)

    fun waitRemainingMs(): Long = (prefs.getLong(K_WAIT_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Turns the lock on with this passcode (replacing any other method). */
    fun setPin(pin: String) {
        require(LockPolicy.isValidPin(pin))
        prefs.edit()
            .putString(K_PIN, LockPolicy.hashPin(pin))
            .putString(K_METHOD, LockPolicy.METHOD_PIN)
            .putInt(K_FAILS, 0).putLong(K_WAIT_UNTIL, 0)
            .apply()
        _method.value = LockPolicy.METHOD_PIN
        applyWindowFlagsToAll()
    }

    fun useBiometric() {
        prefs.edit().putString(K_METHOD, LockPolicy.METHOD_BIOMETRIC).remove(K_PIN).apply()
        _method.value = LockPolicy.METHOD_BIOMETRIC
        applyWindowFlagsToAll()
    }

    fun turnOff() {
        prefs.edit().putString(K_METHOD, LockPolicy.METHOD_NONE).remove(K_PIN).putInt(K_FAILS, 0).putLong(K_WAIT_UNTIL, 0).apply()
        _method.value = LockPolicy.METHOD_NONE
        _locked.value = false
        applyWindowFlagsToAll()
    }

    fun setTimeout(sec: Int) {
        prefs.edit().putInt(K_TIMEOUT, sec).apply()
        _timeoutSec.value = sec
    }

    fun setBlockScreenshots(on: Boolean) {
        prefs.edit().putBoolean(K_SECURE, on).apply()
        _blockScreenshots.value = on
        applyWindowFlagsToAll()
    }

    // ------------------------------------------------------------------ the phone's own security

    /** Whether the phone has a screen lock (PIN, pattern or password) set. */
    fun deviceSecure(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Whether the fingerprint option can be used, and a note to show under it. */
    data class BiometricStatus(val available: Boolean, val note: String?)

    fun biometricStatus(): BiometricStatus {
        if (Build.VERSION.SDK_INT < 28) return BiometricStatus(false, "Needs Android 9 or newer")
        if (!deviceSecure()) return BiometricStatus(false, "Set a screen lock on your phone first")
        if (Build.VERSION.SDK_INT >= 30) {
            val bm = app.getSystemService(BiometricManager::class.java)
            // Never let a system quirk take the settings page down: fall back to "available".
            val result = runCatching { bm?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) }.getOrNull()
                ?: return BiometricStatus(true, null)
            return when (result) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus(true, null)
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    BiometricStatus(true, "No fingerprint enrolled yet, so your phone's screen lock is asked instead")
                else -> BiometricStatus(true, "No fingerprint sensor available, so your phone's screen lock is asked instead")
            }
        }
        return BiometricStatus(true, null)
    }

    // ------------------------------------------------------------------ window flags

    fun setHideRecents(on: Boolean) {
        prefs.edit().putBoolean(K_HIDE_RECENTS, on).apply()
        _hideRecents.value = on
        applyWindowFlagsToAll()
    }

    /**
     * Android 13+ can blank just the recent-apps preview. Older versions can only do that with
     * FLAG_SECURE, which also blocks screenshots, so there it's used for both.
     */
    private fun applyWindowFlags(a: Activity) {
        val secure = _blockScreenshots.value || (_hideRecents.value && Build.VERSION.SDK_INT < 33)
        if (secure) {
            a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(!_hideRecents.value && !_blockScreenshots.value)
    }

    /** Window changes must happen on the main thread; settings can change from anywhere. */
    private fun applyWindowFlagsToAll() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { live.keys.toList().forEach { applyWindowFlags(it) } }
    }

    // ------------------------------------------------------------------ foreground / background

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        live[activity] = Unit
        applyWindowFlags(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0 && inBackground) {
            inBackground = false
            val now = SystemClock.elapsedRealtime()
            val skip = now < skipUntil
            skipUntil = 0
            if (!_locked.value && LockPolicy.shouldLock(_method.value, now - leftAt, _timeoutSec.value, skip)) {
                _locked.value = true
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) {
            inBackground = true
            leftAt = SystemClock.elapsedRealtime()
            // "Immediately": cover the app right away so it's already hidden when it comes back.
            if (enabled && _timeoutSec.value == 0 && SystemClock.elapsedRealtime() >= skipUntil) _locked.value = true
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        live.remove(activity)
    }

    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        private const val K_METHOD = "method"
        private const val K_PIN = "pin_hash"
        private const val K_TIMEOUT = "timeout_sec"
        private const val K_SECURE = "block_screenshots"
        private const val K_HIDE_RECENTS = "hide_recents"
        private const val K_FAILS = "fails"
        private const val K_WAIT_UNTIL = "wait_until"

        val TIMEOUTS = listOf(0 to "Immediately", 30 to "30 s", 60 to "1 min", 300 to "5 min")
    }
}

val Context.appLock: AppLock get() = (applicationContext as PocketStashApp).appLock
