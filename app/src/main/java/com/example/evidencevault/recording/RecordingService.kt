package com.example.evidencevault.recording

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.evidencevault.crypto.CryptoVault
import com.example.evidencevault.storage.IntegrityManifestRepo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START_VIDEO = "EV_START_VIDEO"
        const val ACTION_START_AUDIO = "EV_START_AUDIO"
        const val ACTION_STOP = "EV_STOP"

        const val EXTRA_MIC_ENABLED = "mic_enabled"
        const val EXTRA_CAMERA_FACING = "camera_facing"
        private const val TAG = "RecordingService"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var tempFile: File? = null
    private var isVideo = false
    
    private val audioRecorder = AudioRecorder()

    override fun onCreate() {
        super.onCreate()
        NotificationUtils.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            when (intent?.action) {
                ACTION_START_VIDEO -> {
                    isVideo = true
                    val mic = intent.getBooleanExtra(EXTRA_MIC_ENABLED, true)
                    val facing = intent.getStringExtra(EXTRA_CAMERA_FACING) ?: "BACK"
                    startVideoRecording(mic, facing)
                }
                ACTION_START_AUDIO -> {
                    isVideo = false
                    startAudioRecording()
                }
                ACTION_STOP -> stopAnyRecording()
                else -> Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Service crash", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startVideoRecording(micEnabled: Boolean, facing: String) {
        if (activeRecording != null) return

        startForeground(NotificationUtils.NOTIF_ID, NotificationUtils.buildRecordingNotification(this))

        val tmpDir = File(cacheDir, "temp").apply { mkdirs() }
        tempFile = File(tmpDir, "video_tmp_${System.currentTimeMillis()}.mp4")

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val selector = if (facing == "FRONT") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, selector, videoCapture)

            val out = FileOutputOptions.Builder(tempFile!!).build()
            val pending = videoCapture!!.output.prepareRecording(this, out).apply {
                if (micEnabled) withAudioEnabled()
            }

            activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) cleanupAfterStop() else onFileReadyToEncrypt()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startAudioRecording() {
        if (audioRecorder.isRecording()) return
        startForeground(NotificationUtils.NOTIF_ID, NotificationUtils.buildRecordingNotification(this))
        val tmpDir = File(cacheDir, "temp").apply { mkdirs() }
        tempFile = File(tmpDir, "audio_tmp_${System.currentTimeMillis()}.m4a")
        audioRecorder.start(tempFile!!)
    }

    private fun stopAnyRecording() {
        if (isVideo) {
            activeRecording?.stop()
            activeRecording = null
        } else {
            audioRecorder.stop()
            onFileReadyToEncrypt()
        }
    }

    private fun onFileReadyToEncrypt() {
        val tmp = tempFile
        if (tmp == null || !tmp.exists()) {
            cleanupAfterStop()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(tmp.absolutePath)
                val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mmr.release()
                val durationSec = (ms / 1000L).coerceAtLeast(0L)

                val vaultDir = File(filesDir, "evidence").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val ext = if (isVideo) "mp4" else "m4a"
                val prefix = if (isVideo) "video" else "audio"
                val outEnc = File(vaultDir, "${prefix}_${stamp}_d${durationSec}s.$ext.enc")

                CryptoVault.encryptFileTo(tmp, outEnc)
                tmp.delete()
                IntegrityManifestRepo.appendForEvidenceFile(this@RecordingService, outEnc)
            } finally {
                cleanupAfterStop()
            }
        }
    }

    private fun cleanupAfterStop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
        tempFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
