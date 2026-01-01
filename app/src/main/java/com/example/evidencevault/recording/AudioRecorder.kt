package com.example.evidencevault.recording

import android.media.MediaRecorder
import java.io.File

class AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var currentTempFile: File? = null

    fun start(tempFile: File) {
        currentTempFile = tempFile

        val r = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(tempFile.absolutePath)
            prepare()
            start()
        }
        recorder = r
    }

    fun stop(): File? {
        val r = recorder ?: return null
        return try {
            r.stop()
            r.release()
            currentTempFile
        } catch (e: Exception) {
            // si stop() échoue (rare) on nettoie
            currentTempFile?.delete()
            null
        } finally {
            recorder = null
            currentTempFile = null
        }
    }

    fun isRecording(): Boolean = recorder != null
}
