package com.example.evidencevault.crypto

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object HashUtils {

    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(1024 * 64)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toHex()
    }

    fun sha256String(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(s.toByteArray(Charsets.UTF_8))
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { b -> "%02x".format(b) }
}
