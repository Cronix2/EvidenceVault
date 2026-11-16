package com.example.evidencevault.pulse

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Explicit opt-in settings: no upload occurs until a URL/token are configured and enabled. */
object PulseSettings {
    fun show(activity: AppCompatActivity) {
        val enabled = PulseConfig.enabled(activity)
        val status = if (enabled) "Activé" else "Désactivé"
        val options = arrayOf("Configurer l'adresse et le jeton", if (enabled) "Désactiver Pulse" else "Activer Pulse", "Synchroniser maintenant")
        AlertDialog.Builder(activity)
            .setTitle("Pulse · Sauvegarde append-only")
            .setMessage("État : $status\nDestination : ${PulseConfig.endpoint(activity).ifBlank { "non configurée" }}\nLes fichiers chiffrés seront envoyés dès qu'un réseau est disponible. Le serveur doit interdire la suppression et le remplacement.")
            .setItems(options) { _, choice ->
                when (choice) {
                    0 -> configure(activity)
                    1 -> toggle(activity)
                    2 -> {
                        PulseScheduler.once(activity)
                        Toast.makeText(activity, "Synchronisation programmée si Pulse est actif", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun configure(activity: AppCompatActivity) {
        val pad = (18 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val url = EditText(activity).apply {
            hint = "https://serveur.example/v1/evidence"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(PulseConfig.endpoint(activity))
        }
        val token = EditText(activity).apply {
            hint = "Jeton d'envoi (upload-only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(url, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(token, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(activity)
            .setTitle("Serveur Pulse")
            .setMessage("HTTPS obligatoire. L'application n'a pas de commande DELETE sur le serveur. Configuration inactive tant que Pulse n'est pas activé.")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                try {
                    PulseConfig.configure(activity, url.text.toString().trim(), token.text.toString())
                    Toast.makeText(activity, "Serveur Pulse enregistré", Toast.LENGTH_SHORT).show()
                    if (PulseConfig.enabled(activity)) {
                        PulseScheduler.periodic(activity)
                        PulseScheduler.once(activity)
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, "Configuration refusée : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun toggle(activity: AppCompatActivity) {
        if (PulseConfig.enabled(activity)) {
            PulseConfig.setEnabled(activity, false)
            PulseScheduler.stop(activity)
            Toast.makeText(activity, "Pulse désactivé", Toast.LENGTH_SHORT).show()
        } else if (!PulseConfig.configured(activity)) {
            Toast.makeText(activity, "Configurez d'abord le serveur et le jeton", Toast.LENGTH_LONG).show()
            configure(activity)
        } else {
            AlertDialog.Builder(activity)
                .setTitle("Autoriser les transferts automatiques ?")
                .setMessage("Toutes les preuves locales chiffrées et les versions du manifeste pourront être envoyées vers le serveur configuré. Les anciennes versions ne pourront pas être supprimées avec le jeton d'envoi.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Activer") { _, _ ->
                    PulseConfig.setEnabled(activity, true)
                    PulseScheduler.periodic(activity)
                    PulseScheduler.once(activity)
                    Toast.makeText(activity, "Pulse activé", Toast.LENGTH_SHORT).show()
                }.show()
        }
    }
}
