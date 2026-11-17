package com.example.evidencevault

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.widget.SwitchCompat
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
import com.example.evidencevault.recording.RecordingPreviewBridge
import com.example.evidencevault.pulse.PulseScheduler
import android.widget.EditText
import android.text.InputType

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var switchMic: SwitchCompat
    private lateinit var switchVideo: SwitchCompat
    private lateinit var btnRecord: ImageButton
    private lateinit var btnBackPreview: Button
    private lateinit var btnFrontPreview: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentFacing: CameraFacing = CameraFacing.BACK
    private var isRecording = false
    private var isStopping = false
    private val completedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isRecording = false
            isStopping = false
            updateRecordUi()
            bindPreviewIfPossible()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        RecordingPreviewBridge.set(previewView.surfaceProvider)
        switchMic = findViewById(R.id.switchMic)
        switchVideo = findViewById(R.id.switchVideo)
        btnRecord = findViewById(R.id.btnRecord)
        btnBackPreview = findViewById(R.id.btnBackPreview)
        btnFrontPreview = findViewById(R.id.btnFrontPreview)

        findViewById<ImageButton>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            SecuritySettings.show(this, isRecording || isStopping)
        }

        btnBackPreview.setOnClickListener {
            if (isRecording) return@setOnClickListener
            currentFacing = CameraFacing.BACK
            bindPreviewIfPossible()
        }
        btnFrontPreview.setOnClickListener {
            if (isRecording) return@setOnClickListener
            currentFacing = CameraFacing.FRONT
            bindPreviewIfPossible()
        }

        btnRecord.setOnClickListener {
            if (isStopping) return@setOnClickListener
            if (!isRecording) startCapture() else stopCapture()
        }

        switchVideo.setOnCheckedChangeListener { _, checked ->
            switchMic.isEnabled = checked
            switchMic.alpha = if (checked) 1f else 0.5f
            if (!checked) switchMic.isChecked = true
        }
        updateRecordUi()
        // Keep startup independent of optional upload scheduling and camera hardware.
        // A failed optional component must not prevent access to existing evidence.
        try {
            PulseScheduler.periodic(this)
            PulseScheduler.once(this)
        } catch (t: Throwable) {
            android.util.Log.e("EV_STARTUP", "Pulse scheduling unavailable", t)
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

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, completedReceiver, IntentFilter(RecordingService.ACTION_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(completedReceiver)
    }

    override fun onResume() {
        super.onResume()
        isRecording = RecordingService.captureActive
        isStopping = false
        if (::previewView.isInitialized) {
            RecordingPreviewBridge.set(previewView.surfaceProvider)
            updateRecordUi()
            if (!isRecording && cameraProvider != null) bindPreviewIfPossible()
        }
    }

    override fun onDestroy() {
        RecordingPreviewBridge.clear()
        super.onDestroy()
    }

    private fun configureVaultPin() {
        fun chooseNewPin() {
            val field = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "6 à 12 chiffres"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("PIN du coffre")
                .setMessage("Ce code protège l'ouverture de la galerie. Il ne remplace pas le chiffrement du coffre.")
                .setView(field)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer") { _, _ ->
                    val pin = field.text.toString()
                    if (pin.length in 6..12 && pin.all(Char::isDigit)) {
                        VaultPin.set(this, pin)
                        Toast.makeText(this, "PIN enregistré", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "PIN invalide : 6 à 12 chiffres", Toast.LENGTH_LONG).show()
                }
                .show()
        }
        if (!VaultPin.configured(this)) {
            chooseNewPin()
            return
        }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Code actuel"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Changer le PIN")
            .setView(field)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Continuer") { _, _ ->
                if (VaultPin.verify(this, field.text.toString())) chooseNewPin()
                else Toast.makeText(this, "PIN incorrect", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun startPreview() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindPreviewIfPossible()
                } catch (t: Throwable) {
                    android.util.Log.e("EV_CAMERA", "CameraX initialization failed", t)
                    Toast.makeText(this, "Caméra indisponible. Le coffre reste accessible.", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            android.util.Log.e("EV_CAMERA", "CameraX unavailable", t)
            Toast.makeText(this, "Caméra indisponible. Le coffre reste accessible.", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindPreviewIfPossible() {
        val provider = cameraProvider ?: return
        if (isRecording) return 

        try {
        provider.unbindAll()

        val selector = when (currentFacing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

            provider.bindToLifecycle(this, selector, preview)
        } catch (e: Exception) {
            android.util.Log.e("EV_CAMERA", "Preview binding failed", e)
            Toast.makeText(this, "Prévisualisation indisponible", Toast.LENGTH_SHORT).show()
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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            bindPreviewIfPossible()
            Toast.makeText(this, "Impossible de démarrer : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        isRecording = true
        updateRecordUi()
    }

    private fun updateRecordUi() {
        btnRecord.setImageResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record)
        btnRecord.isEnabled = !isStopping
        btnRecord.contentDescription = if (isRecording) "Arrêter l'enregistrement" else "Démarrer l'enregistrement"
        btnBackPreview.isEnabled = !isRecording
        btnFrontPreview.isEnabled = !isRecording
        switchVideo.isEnabled = !isRecording
        switchMic.isEnabled = !isRecording && switchVideo.isChecked
        findViewById<android.widget.TextView>(R.id.recordStatus).text =
            if (isStopping) "FINALISATION EN COURS…" else if (isRecording) "ENREGISTREMENT EN COURS" else "PRÊT À ENREGISTRER"
    }

    private fun stopCapture() {
        val i = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        try {
            startService(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'arrêter : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        isStopping = true
        updateRecordUi()
        // Service announces completion after finalizing the encrypted evidence.
    }
}
