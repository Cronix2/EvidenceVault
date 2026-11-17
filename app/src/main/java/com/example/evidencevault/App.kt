package com.example.evidencevault

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Protect app startup: optional cleanup is best-effort and never blocks the UI.
        // Clean only stale plaintext files used for playback / Pulse credentials.
        // DO NOT purge cache/temp: a failed capture may still be recoverable there.
        Thread {
            try {
                cacheDir.listFiles()?.filter { file ->
                    file.isFile && (file.name.startsWith("ev_play_") ||
                        file.name.startsWith("ev_video_") ||
                        file.name.startsWith("ev_pulse_secret_") ||
                        file.name.startsWith("ev_pulse_read_"))
                }?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.w("EV_CACHE", "Stale temporary cleanup failed", e)
            }
        }.start()

        // Always delegate to Android's original crash handler; a log-only handler
        // can leave the app in an inconsistent state after an uncaught exception.
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("EV_FATAL", "Uncaught exception in ${thread.name}", throwable)
            if (original != null) original.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
