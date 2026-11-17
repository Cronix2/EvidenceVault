package com.example.evidencevault

import android.content.Context
import com.example.evidencevault.pulse.PulseSettings
import com.example.evidencevault.pulse.PulseScheduler
import com.example.evidencevault.pulse.PulseConfig
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit local-only purge. Best effort: flash remnants, OS backups,
 * user-exported archives and remote append-only copies are NOT deleted.
 */
object SecuritySettings {
    fun show(activity: AppCompatActivity, captureActive: Boolean) {
        val entries = arrayOf("Définir / modifier le PIN du coffre",
            "Définir / modifier le PIN d'effacement", "Effacer les preuves de cet appareil",
            "Pulse · sauvegarde automatique")
        AlertDialog.Builder(activity).setTitle("Sécurité")
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> changePin(activity, false)
                    1 -> changePin(activity, true)
                    3 -> PulseSettings.show(activity)
                    2 -> if (captureActive) {
                        Toast.makeText(activity, "Arrêtez d'abord l'enregistrement", Toast.LENGTH_LONG).show()
                    } else requestPurge(activity)
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun pinInput(activity: AppCompatActivity) = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = "6 à 12 chiffres"
    }

    private fun changePin(activity: AppCompatActivity, forWipe: Boolean) {
        val existing = if (forWipe) VaultPin.wipeConfigured(activity) else VaultPin.configured(activity)
        val needsVaultProof = forWipe && VaultPin.configured(activity) && !existing
        if (existing || needsVaultProof) {
            val current = pinInput(activity)
            AlertDialog.Builder(activity)
                .setTitle(if (needsVaultProof) "Autoriser la configuration d'effacement" else "Vérifier le code actuel")
                .setView(current)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Continuer") { _, _ ->
                    val ok = if (needsVaultProof) VaultPin.verify(activity, current.text.toString())
                        else if (forWipe) VaultPin.verifyWipe(activity, current.text.toString())
                        else VaultPin.verify(activity, current.text.toString())
                    if (ok) chooseNewPin(activity, forWipe)
                    else Toast.makeText(activity, "PIN incorrect", Toast.LENGTH_LONG).show()
                }.show()
        } else chooseNewPin(activity, forWipe)
    }

    private fun chooseNewPin(activity: AppCompatActivity, forWipe: Boolean) {
        val field = pinInput(activity)
        AlertDialog.Builder(activity)
            .setTitle(if (forWipe) "PIN d'effacement local" else "PIN du coffre")
            .setMessage("Choisissez un code différent de celui de l'autre action. Ne le perdez pas.")
            .setView(field)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val value = field.text.toString()
                if (value.length !in 6..12 || !value.all(Char::isDigit) ||
                    (forWipe && VaultPin.matchesVaultWithoutPenalty(activity, value)) ||
                    (!forWipe && VaultPin.matchesWipeWithoutPenalty(activity, value))) {
                    Toast.makeText(activity, "Code invalide ou identique à l'autre PIN", Toast.LENGTH_LONG).show()
                } else {
                    if (forWipe) VaultPin.setWipe(activity, value)
                    else VaultPin.set(activity, value)
                    Toast.makeText(activity, "PIN enregistré", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun requestPurge(activity: AppCompatActivity) {
        if (!VaultPin.wipeConfigured(activity)) {
            Toast.makeText(activity, "Configurez d'abord le PIN d'effacement", Toast.LENGTH_LONG).show()
            return
        }
        val code = pinInput(activity)
        AlertDialog.Builder(activity).setTitle("Effacer les données locales ?")
            .setMessage("Action irréversible : invalide la clé du coffre et supprime les fichiers locaux. Les ZIP exportés, sauvegardes, copies sur serveur et traces physiques éventuelles ne seront PAS effacés.")
            .setView(code)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Vérifier") { _, _ ->
                if (!VaultPin.verifyWipe(activity, code.text.toString())) {
                    Toast.makeText(activity, "PIN d'effacement incorrect", Toast.LENGTH_LONG).show()
                } else confirmPurge(activity)
            }.show()
    }

    private fun confirmPurge(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Confirmation définitive")
            .setMessage("Supprimer définitivement l'accès au coffre local ? Même le PIN d'accès sera réinitialisé. Cela ne peut pas supprimer les copies distantes.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Effacer localement") { _, _ ->
                activity.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                            if (ks.containsAlias("evidencevault_aes")) ks.deleteEntry("evidencevault_aes")
                            PulseScheduler.stop(activity)
                            val evidence = File(activity.filesDir, "evidence")
                            val cache = File(activity.cacheDir, "temp")
                            val deletedEvidence = !evidence.exists() || evidence.deleteRecursively()
                            val deletedCache = !cache.exists() || cache.deleteRecursively()
                            activity.cacheDir.listFiles()?.filter {
                                it.name.startsWith("ev_play_") || it.name.startsWith("ev_video_") ||
                                    it.name.startsWith("manifest_plain") || it.name.startsWith("index_plain")
                            }?.forEach { it.delete() }
                            if (deletedEvidence && deletedCache) {
                                PulseConfig.clear(activity)
                                VaultPin.clearCredentials(activity)
                            }
                            deletedEvidence && deletedCache
                        } catch (_: Exception) { false }
                    }
                    Toast.makeText(activity,
                        if (ok) "Coffre local réinitialisé. Copies distantes non supprimées."
                        else "Échec partiel : vérifiez les fichiers locaux.",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}
