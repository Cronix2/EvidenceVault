package com.example.evidencevault

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local access gate, not a substitute for Android Keystore file encryption. */
object VaultPin {
    private const val PREFS = "vault_pin_v1"
    private const val ITERATIONS = 180_000
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configured(ctx: Context): Boolean = prefs(ctx).contains("hash")

    fun set(ctx: Context, pin: String) {
        require(pin.length in 6..12 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        check(prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .commit())
    }

    fun verify(ctx: Context, pin: String): Boolean {
        if (!configured(ctx) || pin.length !in 6..12) return false
        val p = prefs(ctx)
        val salt = p.getString("salt", null) ?: return false
        val hash = p.getString("hash", null) ?: return false
        return try {
            MessageDigest.isEqual(Base64.decode(hash, Base64.DEFAULT),
                derive(pin, Base64.decode(salt, Base64.DEFAULT)))
        } catch (_: Exception) { false }
    }

    fun wipeConfigured(ctx: Context): Boolean = prefs(ctx).contains("wipeHash")

    fun setWipe(ctx: Context, pin: String) {
        require(pin.length in 6..12 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        check(prefs(ctx).edit()
            .putString("wipeSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("wipeHash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .commit())
    }

    fun verifyWipe(ctx: Context, pin: String): Boolean {
        if (!wipeConfigured(ctx) || pin.length !in 6..12) return false
        val prefs = prefs(ctx)
        val salt = prefs.getString("wipeSalt", null) ?: return false
        val hash = prefs.getString("wipeHash", null) ?: return false
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
