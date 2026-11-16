package com.example.evidencevault.recording

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
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
    private var videoInitializing = false
    private var pendingStop = false
    private var finalizing = false

    private val audioRecorder by lazy { AudioRecorder(this) }

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
            failBeforeCapture("Démarrage impossible : ${t.javaClass.simpleName}. Aucun fichier importé.")
        }
        return START_NOT_STICKY
    }

    private fun startVideoRecording(micEnabled: Boolean, facing: String) {
        if (activeRecording != null || videoInitializing || finalizing) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failBeforeCapture("Autorisation caméra absente.")
            return
        }
        if (micEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failBeforeCapture("Autorisation microphone absente.")
            return
        }
        videoInitializing = true
        pendingStop = false
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (micEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIF_ID, NotificationUtils.buildRecordingNotification(this), types)
        } else startForeground(NotificationUtils.NOTIF_ID, NotificationUtils.buildRecordingNotification(this))
        val tmpDir = File(cacheDir, "temp").apply { mkdirs() }
        val output = File(tmpDir, "video_tmp_${java.util.UUID.randomUUID()}.mp4")
        tempFile = output
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = if (facing == "FRONT") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture
                val surface = RecordingPreviewBridge.get()
                val preview = if (surface != null) Preview.Builder().build().apply {
                    setSurfaceProvider(surface)
                } else null
                provider.unbindAll()
                if (preview != null) provider.bindToLifecycle(this, selector, preview, capture)
                else provider.bindToLifecycle(this, selector, capture)
                val pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(output).build()).apply {
                    if (micEnabled) withAudioEnabled()
                }
                activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        activeRecording = null
                        finalizing = true
                        if (event.hasError()) {
                            Log.e(TAG, "Finalisation vidéo impossible: ${event.error} / ${event.cause}")
                            reportFailure("Capture vidéo échouée : temporaire conservé.")
                            cleanupAfterStop()
                        } else onFileReadyToEncrypt(output)
                    }
                }
                videoInitializing = false
                if (pendingStop) {
                    pendingStop = false
                    activeRecording?.stop()
                }
            } catch (e: Exception) {
                videoInitializing = false
                Log.e(TAG, "Échec lancement CameraX", e)
                failBeforeCapture("Capture vidéo impossible : ${e.javaClass.simpleName}.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startAudioRecording() {
        if (audioRecorder.isRecording()) return

        // Vérifié avant tout service de premier plan de type microphone : sans permission, rien n'est lancé.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failBeforeCapture("Permission micro absente : enregistrement refusé.")
            return
        }

        val tmpDir = File(cacheDir, "temp").apply { mkdirs() }
        val file = audioRecorder.newTempFile(tmpDir)

        // Le premier plan (type microphone) doit être actif avant que le micro ne soit ouvert.
        startAudioForeground()

        val error = audioRecorder.start(file)
        if (error != null) {
            Log.e(TAG, "Audio non démarré: $error")
            reportFailure("Enregistrement audio non démarré : $error. Aucun fichier importé.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Échec avant capture. Android exige un startForeground() après startForegroundService(),
     * donc on l'appelle brièvement avant de s'arrêter (sinon le système peut signaler un crash).
     */
    private fun failBeforeCapture(text: String) {
        try {
            startForeground(NotificationUtils.NOTIF_ERROR_ID, NotificationUtils.buildErrorNotification(this, text))
        } catch (e: Exception) {
            Log.w(TAG, "startForeground impossible pour signaler l'échec", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        reportFailure(text)
        stopSelf()
    }

    private fun startAudioForeground() {
        val notification = NotificationUtils.buildRecordingNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NotificationUtils.NOTIF_ID, notification)
        }
    }

    private fun stopAnyRecording() {
        if (isVideo) {
            if (videoInitializing) pendingStop = true
            else if (!finalizing) activeRecording?.stop()
        } else {
            stopAudioRecording()
        }
    }

    private fun stopAudioRecording() {
        when (val result = audioRecorder.stop()) {
            is AudioRecorder.StopResult.Finalized -> onFileReadyToEncrypt(result.file)
            is AudioRecorder.StopResult.Failed -> {
                // Fichier conservé volontairement : ni chiffré, ni importé, ni supprimé.
                Log.e(TAG, "Audio non finalisé (${result.reason}), fichier ${result.file.name} conservé")
                reportFailure("Enregistrement audio non finalisé (${result.reason}). Fichier temporaire conservé, rien n'a été importé.")
                cleanupAfterStop()
            }
            AudioRecorder.StopResult.NotRecording -> cleanupAfterStop()
        }
    }

    private fun onFileReadyToEncrypt(tmp: File) {
        if (!tmp.exists() || tmp.length() == 0L) {
            reportFailure("Fichier d'enregistrement absent ou vide. Rien n'a été importé.")
            cleanupAfterStop()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mmr = MediaMetadataRetriever()
                val durationSec = try {
                    mmr.setDataSource(tmp.absolutePath)
                    val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    (ms / 1000L).coerceAtLeast(0L)
                } finally {
                    mmr.release()
                }

                val vaultDir = File(filesDir, "evidence").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val ext = if (isVideo) "mp4" else "m4a"
                val prefix = if (isVideo) "video" else "audio"
                val outEnc = File(vaultDir, "${prefix}_${stamp}_d${durationSec}s.$ext.enc")

                CryptoVault.encryptFileTo(tmp, outEnc)
                IntegrityManifestRepo.appendForEvidenceFile(this@RecordingService, outEnc)
                // Suppression du temporaire seulement après un chiffrement et une entrée de manifeste réussis.
                tmp.delete()
            } catch (t: Throwable) {
                Log.e(TAG, "Traitement du fichier échoué, temporaire conservé", t)
                reportFailure("Échec du traitement de l'enregistrement (${t.javaClass.simpleName}). Fichier temporaire conservé, non importé.")
            } finally {
                cleanupAfterStop()
            }
        }
    }

    private fun reportFailure(text: String) {
        // Sans POST_NOTIFICATIONS (Android 13+), l'erreur est seulement journalisée : rien n'est importé dans tous les cas.
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                nm.notify(NotificationUtils.NOTIF_ERROR_ID, NotificationUtils.buildErrorNotification(this, text))
            } else {
                Log.e(TAG, "Notification refusée, erreur non affichée : $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification d'erreur impossible", e)
        }
    }

    private fun cleanupAfterStop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
        tempFile = null
        videoInitializing = false
        pendingStop = false
        finalizing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (audioRecorder.isRecording()) {
            // Service arrêté par le système pendant une capture : finalisation propre, aucun import automatique.
            Log.w(TAG, "Service détruit pendant une capture audio")
            audioRecorder.stop()
            reportFailure("Capture audio interrompue par le système. Fichier temporaire conservé, rien n'a été importé.")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
