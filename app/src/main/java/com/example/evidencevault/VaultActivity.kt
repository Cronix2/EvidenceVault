package com.example.evidencevault

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import android.widget.VideoView
import android.widget.MediaController
import com.google.android.material.chip.ChipGroup
import android.text.InputType
import android.content.DialogInterface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.evidencevault.crypto.CryptoVault
import com.example.evidencevault.crypto.HashUtils
import com.example.evidencevault.domain.Evidence
import com.example.evidencevault.domain.IntegrityStatus
import com.example.evidencevault.storage.EvidenceIndex
import com.example.evidencevault.storage.ExportUtils
import com.example.evidencevault.storage.IntegrityManifestRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class VaultActivity : AppCompatActivity() {

    private lateinit var recyclerEvidence: RecyclerView
    private lateinit var evidenceAdapter: EvidenceAdapter
    private lateinit var searchBar: EditText

    private var allEvidences: List<Evidence> = emptyList()
    private var mediaFilter = "all"
    private var titles: MutableMap<String, String> = mutableMapOf()

    private var mediaPlayer: MediaPlayer? = null
    private var tempPlaybackFile: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var unlocked = false
    private var failedAttempts = 0

    private val createZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)

        recyclerEvidence = findViewById(R.id.recyclerEvidence)
        searchBar = findViewById(R.id.searchBar)
        recyclerEvidence.layoutManager = LinearLayoutManager(this)

        evidenceAdapter = EvidenceAdapter(
            onPlayClick = { evidence, holder -> playEvidence(evidence, holder) },
            onPauseClick = { pausePlayback() },
            onSeekBarChange = { progress -> mediaPlayer?.seekTo(progress) },
            onRenameClick = { showRenameDialog(it) },
            onIntegrityClick = { showIntegrityToast(it.integrity) }
        )
        recyclerEvidence.adapter = evidenceAdapter

        findViewById<android.view.View>(R.id.btnRecord).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnExport).setOnClickListener { askExport() }

        if (VaultPin.configured(this)) {
            findViewById<android.view.View>(R.id.vaultContent).visibility = android.view.View.INVISIBLE
            showUnlockPrompt()
        } else unlocked = true

        findViewById<ChipGroup>(R.id.filterChips).setOnCheckedStateChangeListener { _, selected ->
            mediaFilter = when (selected.firstOrNull()) {
                R.id.chipAudio -> "audio"
                R.id.chipVideo -> "video"
                else -> "all"
            }
            filterList(searchBar.text?.toString().orEmpty())
        }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterList(query: String) {
        val filtered = allEvidences.filter { evidence ->
            val matchesType = when (mediaFilter) {
                "audio" -> evidence.file.name.endsWith(".m4a.enc")
                "video" -> evidence.file.name.endsWith(".mp4.enc")
                else -> true
            }
            matchesType && (evidence.title.contains(query, ignoreCase = true) ||
                evidence.dateText.contains(query, ignoreCase = true))
        }
        cleanupMediaPlayer()
        evidenceAdapter.updateEvidences(filtered)
        val empty = findViewById<android.widget.TextView>(R.id.emptyState)
        empty.text = if (allEvidences.isEmpty()) "Aucune preuve pour le moment"
            else "Aucun enregistrement correspondant"
        empty.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (unlocked) loadList()
    }

    private fun showUnlockPrompt() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN du coffre"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Déverrouiller le coffre")
            .setView(field)
            .setCancelable(false)
            .setNegativeButton("Retour") { _, _ -> finish() }
            .setPositiveButton("Déverrouiller", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (VaultPin.verify(this, field.text.toString())) {
                    unlocked = true
                    failedAttempts = 0
                    findViewById<android.view.View>(R.id.vaultContent).visibility = android.view.View.VISIBLE
                    dialog.dismiss()
                    loadList()
                } else {
                    failedAttempts++
                    field.text.clear()
                    Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
                    if (failedAttempts >= 5) {
                        dialog.dismiss()
                        finish()
                    }
                }
            }
        }
        if (BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS) {
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "Biométrie") { _, _ ->
                val prompt = BiometricPrompt(this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            unlocked = true
                            failedAttempts = 0
                            findViewById<android.view.View>(R.id.vaultContent).visibility = android.view.View.VISIBLE
                            loadList()
                        }
                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            super.onAuthenticationError(code, message)
                            if (!unlocked) showUnlockPrompt()
                        }
                    })
                prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Déverrouiller les preuves")
                    .setSubtitle("Empreinte ou autre biométrie autorisée par Android")
                    .setNegativeButtonText("Utiliser le PIN")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build())
            }
        }
        dialog.show()
    }

    override fun onStop() {
        super.onStop()
        cleanupMediaPlayer()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val (evidenceList, loadedTitles) = withContext(Dispatchers.IO) {
                val titlesMap = EvidenceIndex.loadTitles(this@VaultActivity)
                val snap = IntegrityManifestRepo.snapshot(this@VaultActivity)

                val vaultDir = File(filesDir, "evidence").apply { mkdirs() }
                val files = vaultDir.listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".m4a.enc") || it.name.endsWith(".mp4.enc")) }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                val evidences = files.map { f ->
                    val status = if (!snap.chainOk) {
                        IntegrityStatus.UNVERIFIED
                    } else {
                        val expectedHash = snap.expectedHashes[f.name]
                        if (expectedHash == null) {
                            IntegrityStatus.UNVERIFIED
                        } else {
                            val actualHash = HashUtils.sha256File(f)
                            if (actualHash == expectedHash) IntegrityStatus.OK else IntegrityStatus.MODIFIED
                        }
                    }

                    val baseTitle = titlesMap[f.name].takeUnless { it.isNullOrBlank() } ?: f.name
                    val dateText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(f.lastModified()))
                    val durationSec = extractDurationFromName(f.name)
                    Evidence(f, baseTitle, dateText, formatMMSS(durationSec), status)
                }
                Pair(evidences, titlesMap)
            }
            allEvidences = evidenceList
            this@VaultActivity.titles = loadedTitles.toMutableMap()
            filterList(searchBar.text?.toString().orEmpty())

        }
    }

    private fun playEvidence(evidence: Evidence, holder: EvidenceAdapter.EvidenceViewHolder) {
        cleanupMediaPlayer()
        if (evidence.file.name.endsWith(".mp4.enc", ignoreCase = true)) {
            playVideoEvidence(evidence)
            return
        }

        lifecycleScope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                val outTemp = File.createTempFile("ev_play_", ".m4a", cacheDir)
                try {
                    CryptoVault.decryptFileTo(evidence.file, outTemp)
                    outTemp
                } catch (e: Exception) {
                    outTemp.delete()
                    null
                }
            }
            if (tempFile == null) {
                Toast.makeText(this@VaultActivity, "Erreur de lecture", Toast.LENGTH_LONG).show()
                cleanupMediaPlayer()
                return@launch
            }
            tempPlaybackFile = tempFile
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener { cleanupMediaPlayer() }
                    prepare()
                    start()
                    evidenceAdapter.updatePlaybackState(true)
                    holder.itemSeekBar.max = duration
                    startSeekBarUpdate(holder)
                } catch (e: Exception) {
                    cleanupMediaPlayer()
                    Toast.makeText(this@VaultActivity, "Lecture impossible", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun playVideoEvidence(evidence: Evidence) {
        lifecycleScope.launch {
            val decrypted = withContext(Dispatchers.IO) {
                val temp = File.createTempFile("ev_video_", ".mp4", cacheDir)
                try {
                    CryptoVault.decryptFileTo(evidence.file, temp)
                    temp
                } catch (_: Exception) {
                    temp.delete()
                    null
                }
            }
            if (decrypted == null) {
                Toast.makeText(this@VaultActivity, "Vidéo illisible ou corrompue", Toast.LENGTH_LONG).show()
                return@launch
            }
            val player = VideoView(this@VaultActivity).apply {
                minimumHeight = (260f * resources.displayMetrics.density).toInt()
            }
            val dialog = AlertDialog.Builder(this@VaultActivity)
                .setTitle(evidence.title)
                .setView(player)
                .setPositiveButton("Fermer", null)
                .create()
            dialog.setOnDismissListener {
                player.stopPlayback()
                decrypted.delete()
            }
            dialog.show()
            player.setVideoPath(decrypted.absolutePath)
            player.setMediaController(MediaController(this@VaultActivity).apply { setAnchorView(player) })
            player.setOnPreparedListener { player.start() }
            player.setOnErrorListener { _, _, _ ->
                Toast.makeText(this@VaultActivity, "Lecture vidéo impossible", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                true
            }
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        evidenceAdapter.updatePlaybackState(false)
    }

    private fun startSeekBarUpdate(holder: EvidenceAdapter.EvidenceViewHolder) {
        uiHandler.post(object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        holder.itemSeekBar.progress = it.currentPosition
                        holder.itemCurrentTime.text = formatMMSS((it.currentPosition / 1000).toLong())
                        uiHandler.postDelayed(this, 250)
                    }
                }
            }
        })
    }

    private fun cleanupMediaPlayer() {
        uiHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        tempPlaybackFile?.delete()
        tempPlaybackFile = null
        evidenceAdapter.updatePlaybackState(false)
    }
    
    private fun showRenameDialog(ev: Evidence) {
        val input = EditText(this).apply {
            setText(ev.title)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Renommer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newTitle = input.text?.toString()?.trim().orEmpty()

                lifecycleScope.launch(Dispatchers.IO) { 
                    val currentTitles = titles.toMutableMap()
                    if (newTitle.isNotBlank()) {
                        currentTitles[ev.file.name] = newTitle
                    } else {
                        currentTitles.remove(ev.file.name)
                    }
                    EvidenceIndex.saveTitles(this@VaultActivity, currentTitles)

                    withContext(Dispatchers.Main) { 
                        loadList()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askExport() {
        val name = "EvidenceVault_bundle_${System.currentTimeMillis()}.zip"
        createZipLauncher.launch(name)
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val evidenceDir = File(filesDir, "evidence").apply { mkdirs() }
                    val filesToExport = allEvidences.map { it.file } 
                    ExportUtils.exportZip(contentResolver, uri, evidenceDir, filesToExport)
                }
                Toast.makeText(this@VaultActivity, "Export terminé", Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Toast.makeText(this@VaultActivity, "Export échoué: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showIntegrityToast(status: IntegrityStatus) {
        val msg = when (status) {
            IntegrityStatus.OK -> "Intégrité du fichier : OK"
            IntegrityStatus.MODIFIED -> "ATTENTION : Ce fichier a été modifié !"
            IntegrityStatus.UNVERIFIED -> "Ce fichier n\'est pas suivi par le manifeste d\'intégrité."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun extractDurationFromName(name: String): Long {
        val p = Pattern.compile("_d(\\d+)s")
        val m = p.matcher(name)
        return if (m.find()) m.group(1)?.toLongOrNull() ?: 0L else 0L
    }

    private fun formatMMSS(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}
