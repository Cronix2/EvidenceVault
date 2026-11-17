package com.example.evidencevault.pulse

import android.content.Context
import com.example.evidencevault.crypto.CryptoVault
import java.io.File
import java.net.URI
import java.util.UUID

/** User opt-in. The token is kept encrypted under Android Keystore, not in preferences. */
object PulseConfig {
    private const val PREFS = "ev_pulse"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun secretFile(ctx: Context) = File(ctx.filesDir, "pulse_token.enc")

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun endpoint(ctx: Context): String = prefs(ctx).getString("endpoint", "").orEmpty()

    fun configured(ctx: Context): Boolean =
        endpoint(ctx).isNotBlank() && secretFile(ctx).isFile

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        val old = p.getString("device_id", null)
        if (!old.isNullOrBlank()) return old
        val value = UUID.randomUUID().toString()
        p.edit().putString("device_id", value).apply()
        return value
    }

    /** Accept only a full HTTPS endpoint, never arbitrary cleartext HTTP. */
    fun configure(ctx: Context, url: String, token: String) {
        val uri = URI.create(url.trim())
        require(uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.fragment == null && uri.query == null) {
            "URL HTTPS invalide"
        }
        require(token.length in 16..1024 && !token.contains('\n')) { "Jeton d'envoi invalide" }
        val temp = File.createTempFile("ev_pulse_secret_", ".txt", ctx.cacheDir)
        try {
            temp.writeText(token, Charsets.UTF_8)
            CryptoVault.encryptFileTo(temp, secretFile(ctx))
            check(prefs(ctx).edit().putString("endpoint", uri.toASCIIString()).commit())
        } finally {
            temp.delete()
        }
    }

    fun token(ctx: Context): String? {
        val enc = secretFile(ctx)
        if (!enc.isFile) return null
        val temp = File.createTempFile("ev_pulse_read_", ".txt", ctx.cacheDir)
        return try {
            CryptoVault.decryptFileTo(enc, temp)
            temp.readText(Charsets.UTF_8)
        } finally {
            temp.delete()
        }
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        require(!value || configured(ctx)) { "Configurez d'abord le serveur Pulse" }
        prefs(ctx).edit().putBoolean("enabled", value).apply()
    }

    fun clear(ctx: Context) {
        setEnabled(ctx, false)
        secretFile(ctx).delete()
        prefs(ctx).edit().clear().apply()
    }
}
