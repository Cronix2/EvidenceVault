package com.example.evidencevault

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.evidencevault.crypto.CryptoVault
import java.io.File

class VaultActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null
    private var tempPlaybackFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)

        loadList()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupPlayer()
    }

    private fun loadList() {
        val listView = findViewById<ListView>(R.id.listEvidence)

        val vaultDir = File(filesDir, "evidence").apply { mkdirs() }
        val files = vaultDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".enc") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            Toast.makeText(this, "Aucun enregistrement", Toast.LENGTH_SHORT).show()
        }

        val labels = files.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val encFile = files[position]
            playEncrypted(encFile)
        }
    }

    private fun playEncrypted(encFile: File) {
        try {
            cleanupPlayer()

            // Déchiffre dans un fichier temporaire interne
            val tempDir = File(cacheDir, "playback").apply { mkdirs() }
            val outTemp = File(tempDir, "play_${System.currentTimeMillis()}.m4a")
            CryptoVault.decryptFileTo(encFile, outTemp)
            tempPlaybackFile = outTemp

            player = MediaPlayer().apply {
                setDataSource(outTemp.absolutePath)
                setOnCompletionListener {
                    cleanupPlayer()
                    Toast.makeText(this@VaultActivity, "Lecture terminée", Toast.LENGTH_SHORT).show()
                }
                prepare()
                start()
            }

            Toast.makeText(this, "Lecture : ${encFile.name}", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e("VaultActivity", "Playback error", e)
            Toast.makeText(this, "Erreur lecture (décryptage ou format)", Toast.LENGTH_LONG).show()
            cleanupPlayer()
        }
    }

    private fun cleanupPlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {}
        player?.release()
        player = null

        tempPlaybackFile?.delete()
        tempPlaybackFile = null
    }
}
