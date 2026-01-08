package com.example.evidencevault.storage

import android.content.Context
import com.example.evidencevault.crypto.CryptoVault
import com.example.evidencevault.crypto.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object IntegrityManifestRepo {

    private const val MANIFEST_FILE_NAME = "manifest.json.enc"
    private const val SCHEMA_VERSION = 1
    private const val GENESIS_PREV = "0000000000000000000000000000000000000000000000000000000000000000"

    private fun evidenceDir(context: Context): File =
        File(context.filesDir, "evidence").apply { mkdirs() }

    private fun manifestEncFile(context: Context): File =
        File(evidenceDir(context), MANIFEST_FILE_NAME)

    /**
     * Append-only: ajoute une entrée correspondant à un fichier .enc déjà écrit.
     */
    suspend fun appendForEvidenceFile(context: Context, evidenceEncFile: File) = withContext(Dispatchers.IO) {
        require(evidenceEncFile.exists()) { "Evidence file does not exist" }
        require(evidenceEncFile.name.endsWith(".enc")) { "Evidence file must be .enc" }

        val manifest = loadManifestPlain(context)
        val entries = manifest.getJSONArray("entries")

        val prevEntryHash = manifest.optString("last_entry_hash", GENESIS_PREV)
            .ifBlank { GENESIS_PREV }

        val fileHash = HashUtils.sha256File(evidenceEncFile)
        val filename = evidenceEncFile.name

        val entryHash = HashUtils.sha256String("$filename|$fileHash|$prevEntryHash")

        val entry = JSONObject().apply {
            put("filename", filename)
            put("file_hash", fileHash)
            put("prev_entry_hash", prevEntryHash)
            put("entry_hash", entryHash)
        }

        entries.put(entry)
        manifest.put("last_entry_hash", entryHash)

        saveManifestPlainEncrypted(context, manifest)
    }

    /**
     * Vérifie:
     * 1) Chaîne de hash intacte
     * 2) Chaque fichier listé existe encore
     * 3) Hash de chaque fichier correspond
     */
    suspend fun verify(context: Context): VerificationResult = withContext(Dispatchers.IO) {
        val manifestEnc = manifestEncFile(context)
        if (!manifestEnc.exists()) return@withContext VerificationResult.NoManifest

        val manifest = loadManifestPlain(context)
        val entries = manifest.getJSONArray("entries")

        var expectedPrev = GENESIS_PREV

        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)

            val filename = e.getString("filename")
            val fileHash = e.getString("file_hash")
            val prev = e.getString("prev_entry_hash")
            val entryHash = e.getString("entry_hash")

            // Vérif chaînage
            if (prev != expectedPrev) {
                return@withContext VerificationResult.BrokenChain(index = i)
            }

            val recomputedEntryHash = HashUtils.sha256String("$filename|$fileHash|$prev")
            if (recomputedEntryHash != entryHash) {
                return@withContext VerificationResult.EntryHashMismatch(index = i)
            }

            // Vérif existence + hash fichier
            val f = File(evidenceDir(context), filename)
            if (!f.exists()) {
                return@withContext VerificationResult.MissingEvidenceFile(filename = filename)
            }

            val recomputedFileHash = HashUtils.sha256File(f)
            if (recomputedFileHash != fileHash) {
                return@withContext VerificationResult.FileHashMismatch(filename = filename)
            }

            expectedPrev = entryHash
        }

        // Vérif last_entry_hash
        val last = manifest.optString("last_entry_hash", GENESIS_PREV)
        if (entries.length() == 0 && last != GENESIS_PREV) {
            return@withContext VerificationResult.ManifestCorrupted
        }
        if (entries.length() > 0) {
            val lastEntry = entries.getJSONObject(entries.length() - 1).getString("entry_hash")
            if (last != lastEntry) return@withContext VerificationResult.ManifestCorrupted
        }

        VerificationResult.Ok(totalEntries = entries.length())
    }

    // ----------------- internes (plain JSON <-> chiffré) -----------------

    private fun loadManifestPlain(context: Context): JSONObject {
        val enc = manifestEncFile(context)
        if (!enc.exists()) {
            return JSONObject().apply {
                put("schema_version", SCHEMA_VERSION)
                put("last_entry_hash", GENESIS_PREV)
                put("entries", JSONArray())
            }
        }

        val temp = File(context.cacheDir, "manifest_plain.json")
        return try {
            CryptoVault.decryptFileTo(enc, temp)
            val text = temp.readText(Charsets.UTF_8)
            JSONObject(text)
        } finally {
            temp.delete()
        }
    }

    private fun saveManifestPlainEncrypted(context: Context, manifest: JSONObject) {
        val temp = File(context.cacheDir, "manifest_plain_write.json")
        val enc = manifestEncFile(context)

        temp.writeText(manifest.toString(), Charsets.UTF_8)
        CryptoVault.encryptFileTo(temp, enc)
        temp.delete()
    }

    sealed class VerificationResult {
        data class Ok(val totalEntries: Int) : VerificationResult()
        data object NoManifest : VerificationResult()
        data object ManifestCorrupted : VerificationResult()
        data class BrokenChain(val index: Int) : VerificationResult()
        data class EntryHashMismatch(val index: Int) : VerificationResult()
        data class MissingEvidenceFile(val filename: String) : VerificationResult()
        data class FileHashMismatch(val filename: String) : VerificationResult()
    }
}
