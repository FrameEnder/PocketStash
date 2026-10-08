package com.frameender.pocketstash.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parts of the app lock that are plain logic (no Android): passcode hashing and the rules
 * for when to lock and how long to wait after wrong guesses. Kept separate so they can be tested.
 */
object LockPolicy {
    const val METHOD_NONE = "none"
    const val METHOD_PIN = "pin"
    const val METHOD_BIOMETRIC = "biometric"

    const val MIN_PIN = 4
    const val MAX_PIN = 12

    /** Wrong guesses allowed before the first wait. */
    const val FREE_TRIES = 5

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    /** "v1:<iterations>:<salt>:<hash>", all base64. The passcode itself is never stored. */
    fun hashPin(pin: String, salt: ByteArray = randomSalt(), iterations: Int = ITERATIONS): String {
        val hash = derive(pin, salt, iterations)
        val b64 = Base64.getEncoder()
        return "v1:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}"
    }

    fun verifyPin(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != "v1") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return false
        // Constant-time comparison.
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    fun isValidPin(pin: String): Boolean = pin.length in MIN_PIN..MAX_PIN && pin.all { it in '0'..'9' }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /**
     * How long to wait (ms) after [fails] wrong guesses in a row: nothing for the first
     * [FREE_TRIES], then 30 s, doubling each time, up to 15 minutes.
     */
    fun waitAfter(fails: Int): Long {
        if (fails < FREE_TRIES) return 0
        val step = (fails - FREE_TRIES).coerceAtMost(5)
        return (30_000L shl step).coerceAtMost(15 * 60_000L)
    }

    /**
     * Whether returning to the app after being away should ask again.
     * [awayMs] is how long it was in the background, [timeoutSec] the "lock after" setting
     * (0 = immediately), and [skip] is set when the app itself opened something (a file
     * picker, the phone's lock screen) and expects to come straight back.
     */
    fun shouldLock(method: String, awayMs: Long, timeoutSec: Int, skip: Boolean): Boolean {
        if (method == METHOD_NONE || skip) return false
        return awayMs >= timeoutSec * 1000L
    }
}
