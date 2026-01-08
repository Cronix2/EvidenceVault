package com.example.evidencevault.storage

import android.content.Context
import com.example.evidencevault.crypto.CryptoVault
import org.json.JSONObject
import java.io.File

object EvidenceIndex {
    private const val INDEX_FILE_NAME = "index.json.enc"

    private fun indexFile(context: Context): File {
        val dir = File(context.filesDir, "evidence").apply { mkdirs() }
        return File(dir, INDEX_FILE_NAME)
    }

    fun loadTitles(context: Context): MutableMap<String, String> {
        val f = indexFile(context)
        if (!f.exists()) return mutableMapOf()

        val temp = File(context.cacheDir, "index_plain.json")
        return try {
            CryptoVault.decryptFileTo(f, temp)
            val text = temp.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val titlesObj = root.optJSONObject("titles") ?: JSONObject()

            val map = mutableMapOf<String, String>()
            val keys = titlesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = titlesObj.optString(k, "")
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        } finally {
            temp.delete()
        }
    }

    fun saveTitles(context: Context, titles: Map<String, String>) {
        val plain = JSONObject().apply {
            val t = JSONObject()
            titles.forEach { (fileName, title) ->
                t.put(fileName, title)
            }
            put("titles", t)
        }.toString()

        val temp = File(context.cacheDir, "index_plain_write.json")
        val out = indexFile(context)

        temp.writeText(plain, Charsets.UTF_8)
        CryptoVault.encryptFileTo(temp, out)
        temp.delete()
    }
}
