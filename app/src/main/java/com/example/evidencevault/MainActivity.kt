package com.example.evidencevault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.evidencevault.domain.CameraFacing
import com.example.evidencevault.recording.RecordingService

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var switchMic: Switch
    private lateinit var switchVideo: Switch
    private lateinit var btnRecord: ImageButton
    private lateinit var btnBackPreview: Button
    private lateinit var btnFrontPreview: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentFacing: CameraFacing = CameraFacing.BACK
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        switchMic = findViewById(R.id.switchMic)
        switchVideo = findViewById(R.id.switchVideo)
        btnRecord = findViewById(R.id.btnRecord)
        btnBackPreview = findViewById(R.id.btnBackPreview)
        btnFrontPreview = findViewById(R.id.btnFrontPreview)

        findViewById<ImageButton>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        btnBackPreview.setOnClickListener {
            currentFacing = CameraFacing.BACK
            bindPreviewIfPossible()
        }
        btnFrontPreview.setOnClickListener {
            currentFacing = CameraFacing.FRONT
            bindPreviewIfPossible()
        }

        btnRecord.setOnClickListener {
            if (!isRecording) startCapture()
            else stopCapture()
        }

        ensurePermissionsThenStartPreview()
    }

    // Seule la caméra est demandée au lancement (prévisualisation). Le micro est demandé à la demande, au moment de l'enregistrement.
    private fun ensurePermissionsThenStartPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        } else {
            startPreview()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            1001 -> if (granted) {
                startPreview()
            } else {
                Toast.makeText(this, "Permission caméra requise pour la prévisualisation", Toast.LENGTH_LONG).show()
            }
            1002 -> if (granted) {
                Toast.makeText(this, "Micro autorisé : appuyez à nouveau sur enregistrer", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enregistrement refusé : permission micro absente", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMicPermissionWithRationale() {
        AlertDialog.Builder(this)
            .setTitle("Autoriser le micro ?")
            .setMessage(
                "Le micro sert uniquement à enregistrer le son de cette capture, et seulement après votre appui sur enregistrer. " +
                    "Sans cette autorisation, la capture ne démarre pas."
            )
            .setPositiveButton("Autoriser") { _, _ ->
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startPreview() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindPreviewIfPossible()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreviewIfPossible() {
        val provider = cameraProvider ?: return
        if (isRecording) return 

        provider.unbindAll()

        val selector = when (currentFacing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        try {
            provider.bindToLifecycle(this, selector, preview)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur preview: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCapture() {
        val video = switchVideo.isChecked
        // La capture audio seule utilise toujours le micro.
        val mic = switchMic.isChecked || !video
        val facing = currentFacing.name

        if (mic && !hasMicPermission()) {
            askMicPermissionWithRationale()
            return
        }

        cameraProvider?.unbindAll()

        val i = Intent(this, RecordingService::class.java).apply {
            action = if (video) RecordingService.ACTION_START_VIDEO else RecordingService.ACTION_START_AUDIO
            putExtra(RecordingService.EXTRA_MIC_ENABLED, mic)
            putExtra(RecordingService.EXTRA_CAMERA_FACING, facing)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)

        isRecording = true
        btnRecord.setImageResource(R.drawable.ic_stop)
    }

    private fun stopCapture() {
        val i = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(i)

        isRecording = false
        btnRecord.setImageResource(R.drawable.ic_record)

        bindPreviewIfPossible()
    }
}
