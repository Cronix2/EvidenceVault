package com.example.evidencevault

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.evidencevault.crypto.CryptoVault
import com.example.evidencevault.domain.Evidence
import com.example.evidencevault.storage.EvidenceIndex
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
    private var titles: MutableMap<String, String> = mutableMapOf()

    private var mediaPlayer: MediaPlayer? = null
    private var tempPlaybackFile: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())

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
            onRenameClick = { showRenameDialog(it) }
        )
        recyclerEvidence.adapter = evidenceAdapter

        findViewById<ImageButton>(R.id.btnRecord).setOnClickListener { finish() }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Lancer la vérification de l'intégrité au démarrage
        lifecycleScope.launch {
            val result = IntegrityManifestRepo.verify(this@VaultActivity)
            showIntegrityStatus(result)
        }
    }

    private fun filterList(query: String) {
        val filteredList = allEvidences.filter { 
            it.title.contains(query, ignoreCase = true) || 
            it.dateText.contains(query, ignoreCase = true) 
        }
        evidenceAdapter.updateEvidences(filteredList)
    }

    override fun onResume() {
        super.onResume()
        loadList()
    }

    override fun onStop() {
        super.onStop()
        cleanupMediaPlayer()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val (evidenceList, loadedTitles) = withContext(Dispatchers.IO) {
                val titles = EvidenceIndex.loadTitles(this@VaultActivity)
                val vaultDir = File(filesDir, "evidence").apply { mkdirs() }
                val files = vaultDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".enc") && it.name != "index.json.enc" && it.name != "manifest.json.enc" }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                val evidences = files.map { f ->
                    val baseTitle = titles[f.name].takeUnless { it.isNullOrBlank() } ?: f.name
                    val dateText = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(f.lastModified()))
                    val durationSec = extractDurationFromName(f.name)
                    Evidence(f, baseTitle, dateText, formatMMSS(durationSec))
                }
                Pair(evidences, titles)
            }
            allEvidences = evidenceList
            this@VaultActivity.titles = loadedTitles
            evidenceAdapter.updateEvidences(evidenceList)
            if (evidenceList.isEmpty()) {
                Toast.makeText(this@VaultActivity, "Aucun enregistrement", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playEvidence(evidence: Evidence, holder: EvidenceAdapter.EvidenceViewHolder) {
        cleanupMediaPlayer()

        lifecycleScope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                try {
                    val outTemp = File(cacheDir, "play.m4a")
                    CryptoVault.decryptFileTo(evidence.file, outTemp)
                    outTemp
                } catch (e: Exception) { null }
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
                }
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

                lifecycleScope.launch(Dispatchers.IO) { // Sauvegarde en arrière-plan
                    val currentTitles = titles.toMutableMap()
                    if (newTitle.isNotBlank()) {
                        currentTitles[ev.file.name] = newTitle
                    } else {
                        currentTitles.remove(ev.file.name)
                    }
                    EvidenceIndex.saveTitles(this@VaultActivity, currentTitles)

                    withContext(Dispatchers.Main) { // Rechargement sur le thread UI
                        loadList()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showIntegrityStatus(result: IntegrityManifestRepo.VerificationResult) {
        val msg = when (result) {
            is IntegrityManifestRepo.VerificationResult.Ok ->
                "Intégrité OK (${result.totalEntries} preuves)"
            IntegrityManifestRepo.VerificationResult.NoManifest ->
                "Pas de manifeste (aucune preuve validée)"
            IntegrityManifestRepo.VerificationResult.ManifestCorrupted ->
                "Manifeste corrompu"
            is IntegrityManifestRepo.VerificationResult.BrokenChain ->
                "Chaîne cassée (entrée ${result.index})"
            is IntegrityManifestRepo.VerificationResult.EntryHashMismatch ->
                "Hash entrée invalide (entrée ${result.index})"
            is IntegrityManifestRepo.VerificationResult.MissingEvidenceFile ->
                "Preuve manquante: ${result.filename}"
            is IntegrityManifestRepo.VerificationResult.FileHashMismatch ->
                "Preuve modifiée: ${result.filename}"
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
