package com.example.evidencevault.pulse

import android.content.Context
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.example.evidencevault.crypto.HashUtils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Upload-only worker. Sends encrypted blobs; never exposes a delete/update method.
 * Server must enforce append-only semantics on its side.
 */
class PulseUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!PulseConfig.enabled(ctx) || !PulseConfig.configured(ctx)) return Result.success()
        val token = try { PulseConfig.token(ctx) } catch (_: Exception) { return Result.retry() }
            ?: return Result.failure()
        val endpoint = PulseConfig.endpoint(ctx)
        val preferences = ctx.getSharedPreferences("ev_pulse_sent", Context.MODE_PRIVATE)
        val files = File(ctx.filesDir, "evidence").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".enc") }
            ?.sortedBy { it.lastModified() }
            ?: return Result.success()

        for (file in files) {
            val digest = try { HashUtils.sha256File(file) } catch (_: Exception) { return Result.retry() }
            val marker = "sent:${file.name}:$digest"
            if (preferences.getBoolean(marker, false)) continue
            val successful = try {
                upload(endpoint, token, PulseConfig.deviceId(ctx), file, digest)
            } catch (_: Exception) {
                return Result.retry()
            }
            if (!successful) return Result.retry()
            // Persist acknowledgement before proceeding; redelivery is idempotent on the server.
            if (!preferences.edit().putBoolean(marker, true).commit()) return Result.retry()
        }
        return Result.success()
    }

    private fun upload(endpoint: String, token: String, deviceId: String, file: File, digest: String): Boolean {
        val url = URL(endpoint)
        require(url.protocol == "https")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            instanceFollowRedirects = false
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Evidence-Name", Base64.encodeToString(
                file.name.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            ))
            setRequestProperty("X-Evidence-SHA256", digest)
            setRequestProperty("X-Device-ID", deviceId)
        }
        return try {
            conn.outputStream.use { os -> file.inputStream().use { input -> input.copyTo(os, 64 * 1024) } }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }
}

object PulseScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun once(ctx: Context) {
        if (!PulseConfig.enabled(ctx) || !PulseConfig.configured(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "ev-pulse-once", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PulseUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    fun periodic(ctx: Context) {
        if (!PulseConfig.enabled(ctx) || !PulseConfig.configured(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "ev-pulse-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PulseUploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("ev-pulse-once")
        WorkManager.getInstance(ctx).cancelUniqueWork("ev-pulse-periodic")
    }
}
