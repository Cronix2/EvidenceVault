package com.example.evidencevault.recording

import android.app.Service
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.IBinder
import com.example.evidencevault.crypto.CryptoVault
import com.example.evidencevault.storage.IntegrityManifestRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "EV_START"
        const val ACTION_STOP = "EV_STOP"
    }

    private val recorder = AudioRecorder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder.isRecording()) return

        NotificationUtils.ensureChannel(this)
        startForeground(NotificationUtils.NOTIF_ID, NotificationUtils.buildRecordingNotification(this))

        val tempDir = File(cacheDir, "temp").apply { mkdirs() }
        val tmp = File(tempDir, "audio_tmp.m4a")
        recorder.start(tmp)
    }

    private fun stopRecording() {
        stopForeground(STOP_FOREGROUND_REMOVE)

        Thread {
            val tmp = recorder.stop()

            if (tmp != null && tmp.exists()) {
                val vaultDir = File(filesDir, "evidence").apply { mkdirs() }

                val durationSec = try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(tmp.absolutePath)
                    val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    mmr.release()
                    (ms / 1000L).coerceAtLeast(0L)
                } catch (_: Exception) {
                    0L
                }

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val out = File(vaultDir, "audio_${stamp}_d${durationSec}s.m4a.enc")

                CryptoVault.encryptFileTo(tmp, out)
                tmp.delete()

                // Append manifeste en arrière-plan
                CoroutineScope(Dispatchers.IO).launch {
                    IntegrityManifestRepo.appendForEvidenceFile(this@RecordingService, out)
                }
            }

            stopSelf()
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
