package com.example.evidencevault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.evidencevault.recording.RecordingService

class MainActivity : AppCompatActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecordingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startRecordingService()
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            val i = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startService(i)
        }

        findViewById<Button>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }

    private fun startRecordingService() {
        val i = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        // startForegroundService requis Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }
}
