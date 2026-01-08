package com.example.evidencevault.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File

object ExportUtils {

    fun exportZip(
        contentResolver: ContentResolver,
        outUri: Uri,
        evidenceDir: File,
        evidenceFiles: List<File>
    ) {
        contentResolver.openOutputStream(outUri)?.use { os ->
            ZipOutputStream(BufferedOutputStream(os)).use { zos ->

                fun addFile(file: File, entryName: String) {
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 1) manifeste chiffré
                val manifest = File(evidenceDir, "manifest.json.enc")
                if (manifest.exists()) addFile(manifest, "manifest.json.enc")

                // 2) preuves chiffrées
                evidenceFiles.forEach { f ->
                    addFile(f, "evidence/${f.name}")
                }

                // 3) instructions
                val instructions = """
EvidenceVault export bundle

Contenu:
- manifest.json.enc : manifeste d'intégrité chiffré
- evidence/*.enc    : preuves chiffrées (hashées dans le manifeste)

Vérification:
- La vérification complète nécessite EvidenceVault (ou un outil tiers reproduisant le verify()).
- Si un fichier evidence/*.enc est manquant ou modifié, verify() doit échouer.

Note:
- Les fichiers sont chiffrés. Leur contenu n'est lisible que par l'application (clé Keystore).
""".trimIndent()

                zos.putNextEntry(ZipEntry("INSTRUCTIONS.txt"))
                zos.write(instructions.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        } ?: error("Cannot open output stream")
    }
}