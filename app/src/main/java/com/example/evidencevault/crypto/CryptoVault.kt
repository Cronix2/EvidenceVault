package com.example.evidencevault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "evidencevault_aes"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Format fichier chiffré:
     * [12 bytes IV][ciphertext...]
     */
    fun encryptFileTo(tempIn: File, outFile: File) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes en général

        FileInputStream(tempIn).use { fis ->
            FileOutputStream(outFile).use { fos ->
                fos.write(iv)
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    val enc = cipher.update(buffer, 0, read)
                    if (enc != null) fos.write(enc)
                }
                val finalBytes = cipher.doFinal()
                fos.write(finalBytes)
            }
        }
    }

    fun decryptFileTo(encFile: File, outTemp: File) {
        val key = getOrCreateKey()
        FileInputStream(encFile).use { fis ->
            val iv = ByteArray(12)
            val ivRead = fis.read(iv)
            require(ivRead == 12) { "Invalid file format (IV missing)" }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

            FileOutputStream(outTemp).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    val dec = cipher.update(buffer, 0, read)
                    if (dec != null) fos.write(dec)
                }
                val finalBytes = cipher.doFinal()
                fos.write(finalBytes)
            }
        }
    }
}
