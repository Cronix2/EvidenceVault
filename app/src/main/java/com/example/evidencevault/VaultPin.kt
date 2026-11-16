package com.example.evidencevault

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local PIN verifiers: salted PBKDF2 and persisted rate limiting.
 * This is an access control; actual data encryption remains in Android Keystore.
 */
object VaultPin {
    private const val PREFS = "vault_pin_v1"
    private const val ITERATIONS = 180_000
    private const val MAX_FAILURES = 5
    private const val LOCK_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun configured(ctx: Context): Boolean = prefs(ctx).contains("hash")
    fun wipeConfigured(ctx: Context): Boolean = prefs(ctx).contains("wipeHash")

    fun set(ctx: Context, pin: String) = store(ctx, pin, "salt", "hash", "vault")
    fun setWipe(ctx: Context, pin: String) = store(ctx, pin, "wipeSalt", "wipeHash", "wipe")

    private fun store(ctx: Context, pin: String, saltKey: String, hashKey: String, prefix: String) {
        require(pin.length in 6..12 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        check(prefs(ctx).edit()
            .putString(saltKey, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(hashKey, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove("${prefix}Failures")
            .remove("${prefix}LockedUntil")
            .commit())
    }

    fun verify(ctx: Context, pin: String): Boolean = checked(ctx, pin, "salt", "hash", "vault")
    fun verifyWipe(ctx: Context, pin: String): Boolean = checked(ctx, pin, "wipeSalt", "wipeHash", "wipe")

    // Used ONLY when comparing a proposed new PIN to the other PIN.
    fun matchesVaultWithoutPenalty(ctx: Context, pin: String) = matches(ctx, pin, "salt", "hash")
    fun matchesWipeWithoutPenalty(ctx: Context, pin: String) = matches(ctx, pin, "wipeSalt", "wipeHash")

    private fun checked(ctx: Context, pin: String, saltKey: String, hashKey: String, prefix: String): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (p.getLong("${prefix}LockedUntil", 0L) > now) return false
        val valid = matches(ctx, pin, saltKey, hashKey)
        val editor = p.edit()
        if (valid) {
            editor.remove("${prefix}Failures").remove("${prefix}LockedUntil")
        } else {
            val failures = p.getInt("${prefix}Failures", 0) + 1
            if (failures >= MAX_FAILURES) {
                editor.putInt("${prefix}Failures", 0)
                    .putLong("${prefix}LockedUntil", now + LOCK_MS)
            } else {
                editor.putInt("${prefix}Failures", failures)
            }
        }
        editor.commit()
        return valid
    }

    private fun matches(ctx: Context, pin: String, saltKey: String, hashKey: String): Boolean {
        if (pin.length !in 6..12 || !pin.all(Char::isDigit)) return false
        val p = prefs(ctx)
        val salt = p.getString(saltKey, null) ?: return false
        val hash = p.getString(hashKey, null) ?: return false
        return try {
            MessageDigest.isEqual(Base64.decode(hash, Base64.DEFAULT),
                derive(pin, Base64.decode(salt, Base64.DEFAULT)))
        } catch (_: Exception) { false }
    }

    fun clearCredentials(ctx: Context) {
        prefs(ctx).edit().clear().commit()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }
}
