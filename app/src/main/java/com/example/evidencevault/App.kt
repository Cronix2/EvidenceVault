package com.example.evidencevault

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("EV_FATAL", "Uncaught exception in ${thread.name}", throwable)
        }
    }
}
