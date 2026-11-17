package com.example.evidencevault.recording

import androidx.camera.core.Preview
import java.lang.ref.WeakReference

/** Exposes the visible activity's preview surface without retaining the Activity. */
object RecordingPreviewBridge {
    @Volatile private var reference: WeakReference<Preview.SurfaceProvider>? = null

    fun set(surface: Preview.SurfaceProvider) {
        reference = WeakReference(surface)
    }

    fun get(): Preview.SurfaceProvider? = reference?.get()

    fun clear() { reference = null }
}
