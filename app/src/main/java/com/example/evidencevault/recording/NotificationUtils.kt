package com.example.evidencevault.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.evidencevault.R

object NotificationUtils {
    const val CHANNEL_ID = "evidencevault_recording"
    const val CHANNEL_NAME = "EvidenceVault Recording"
    const val NOTIF_ID = 1001
    const val NOTIF_ERROR_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Statut d'enregistrement"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /** Notification persistante : indique la capture en cours et ses limites réelles. */
    fun buildRecordingNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("EvidenceVault : enregistrement en cours")
            .setContentText("Le fichier n'est finalisé qu'à l'arrêt. Une extinction ou un redémarrage du téléphone interrompt la capture.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Le fichier n'est finalisé qu'à l'arrêt. Une extinction ou un redémarrage du téléphone interrompt la capture et la capture ne reprend pas automatiquement."
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Notification d'erreur signalant un échec sans importer le fichier. */
    fun buildErrorNotification(context: Context, text: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("EvidenceVault : erreur d'enregistrement")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
    }
}
