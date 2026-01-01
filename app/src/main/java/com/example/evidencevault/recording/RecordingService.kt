package com.example.evidencevault.recording

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.evidencevault.crypto.CryptoVault
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "EV_START"
        const val ACTION_STOP = "EV_STOP"
        private const val TAG = "RecordingService"
    }

    private val recorder = AudioRecorder()

    override fun onCreate() {
        super.onCreate()
        NotificationUtils.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startRecording()
                ACTION_STOP -> stopRecording()
                else -> Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service crash", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder.isRecording()) return

        // IMPORTANT: must be first
        startForeground(
            NotificationUtils.NOTIF_ID,
            NotificationUtils.buildRecordingNotification(this)
        )

        val tempDir = File(cacheDir, "temp").apply { mkdirs() }
        val tmp = File(tempDir, "audio_tmp.m4a")

        Log.i(TAG, "Start recording: ${tmp.absolutePath}")
        recorder.start(tmp)
    }

    private fun stopRecording() {
        val tmp = recorder.stop()
        if (tmp != null && tmp.exists()) {
            val vaultDir = File(filesDir, "evidence").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(vaultDir, "audio_$stamp.m4a.enc")

            Log.i(TAG, "Encrypt -> ${out.absolutePath}")
            CryptoVault.encryptFileTo(tmp, out)
            tmp.delete()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
