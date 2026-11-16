package com.example.evidencevault.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

/**
 * Capture audio MPEG-4 / AAC via MediaRecorder.
 * Le fichier de sortie est toujours un fichier temporaire privé (cache interne) au nom aléatoire.
 * Aucune suppression ni export n'est fait ici : c'est au service de décider après [stop].
 */
class AudioRecorder(private val context: Context) {

    sealed class StopResult {
        /** Enregistrement finalisé sans erreur connue ; le fichier est prêt à être traité. */
        data class Finalized(val file: File) : StopResult()

        /** Finalisation échouée ; le fichier est conservé tel quel et ne doit pas être importé. */
        data class Failed(val file: File, val reason: String) : StopResult()

        object NotRecording : StopResult()
    }

    private class Session(val recorder: MediaRecorder, val file: File)

    private var session: Session? = null

    @Volatile
    private var recorderError: String? = null

    /** Nom aléatoire (UUID, SecureRandom) dans le dossier fourni. */
    fun newTempFile(dir: File): File = File(dir, "audio_${UUID.randomUUID()}.m4a")

    /**
     * Prépare et démarre la capture dans [file].
     * Retourne null en cas de succès, sinon un message d'erreur. Le MediaRecorder est libéré en cas d'échec.
     */
    fun start(file: File): String? {
        if (session != null) return "Enregistrement déjà en cours"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Permission micro absente"
        }

        recorderError = null
        val r = createRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.setOnErrorListener { _, what, extra ->
                recorderError = "erreur MediaRecorder (what=$what, extra=$extra)"
                Log.e(TAG, "MediaRecorder onError: $what/$extra")
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "Échec préparation/démarrage du MediaRecorder", e)
            releaseQuietly(r)
            return "Démarrage impossible (${e.javaClass.simpleName})"
        }

        session = Session(r, file)
        return null
    }

    /** Arrête et libère le MediaRecorder. Ne supprime jamais le fichier. */
    fun stop(): StopResult {
        val s = session ?: return StopResult.NotRecording
        session = null

        var failure: String? = null
        try {
            s.recorder.stop()
        } catch (e: Exception) {
            // RuntimeException si aucune donnée valide n'a été écrite, ou état invalide
            Log.e(TAG, "Échec de MediaRecorder.stop()", e)
            failure = "arrêt impossible (${e.javaClass.simpleName})"
        } finally {
            releaseQuietly(s.recorder)
        }

        if (failure == null) {
            recorderError?.let { failure = it }
        }
        if (failure == null && (!s.file.exists() || s.file.length() == 0L)) {
            failure = "fichier vide ou absent"
        }

        val reason = failure
        return if (reason == null) StopResult.Finalized(s.file) else StopResult.Failed(s.file, reason)
    }

    fun isRecording(): Boolean = session != null

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun releaseQuietly(r: MediaRecorder) {
        try {
            r.release()
        } catch (e: Exception) {
            Log.w(TAG, "release() a échoué", e)
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
