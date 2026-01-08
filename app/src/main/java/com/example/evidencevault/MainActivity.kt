package com.example.evidencevault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.evidencevault.recording.RecordingService

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: ImageButton

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecordingService()
        } else {
            Toast.makeText(this, "Permission micro requise", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        val btnVault = findViewById<ImageButton>(R.id.btnVault)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        // Appliquer le selector
        btnRecord.setBackgroundResource(R.drawable.record_button_selector)

        btnRecord.setOnClickListener {
            if (!btnRecord.isSelected) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startRecordingService()
                }
            } else {
                stopRecordingService()
            }
        }

        btnVault.setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        btnSettings.setOnClickListener {
            // TODO: Implémenter l'écran des paramètres
            Toast.makeText(this, "Paramètres à venir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecordingService() {
        val i = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        btnRecord.isSelected = true
        btnRecord.setImageResource(R.drawable.ic_stop)
    }

    private fun stopRecordingService() {
        val i = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(i)
        btnRecord.isSelected = false
        btnRecord.setImageResource(R.drawable.ic_record)
    }
}
