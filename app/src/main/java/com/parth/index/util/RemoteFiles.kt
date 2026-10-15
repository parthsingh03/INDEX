package com.parth.index.util

import android.content.Context
import com.parth.index.data.model.MaterialFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Bridges the one difference cloud mode makes to file opening: an
 *  entity's localPath is a Cloudinary URL for shared files, not a path on
 *  this phone. [localCopy] hands back a real File either way — the path
 *  itself when it already is one (this phone's own uploads, local leftovers),
 *  or a downloaded copy in the cache dir for URLs. Downloads are cached by
 *  file id, so reopening the same material does not fetch it twice; the
 *  Profile screen's Clear Cache button wipes them with the rest of the cache. */
object RemoteFiles {

    fun isRemote(file: MaterialFileEntity): Boolean =
        file.localPath.startsWith("http://") || file.localPath.startsWith("https://")

    /** A readable local File for [file], or null when there is nothing to
     *  open (sample entry) or the download failed. Runs on Dispatchers.IO. */
    suspend fun localCopy(context: Context, file: MaterialFileEntity): File? =
        withContext(Dispatchers.IO) {
            val path = file.localPath
            if (path.isEmpty()) return@withContext null
            if (!isRemote(file)) {
                return@withContext File(path).takeIf { it.exists() }
            }
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val safeName = file.fileName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifEmpty { "file" }
            val dest = File(dir, "${file.id}_$safeName")
            if (dest.exists() && dest.length() > 0) return@withContext dest
            if (runCatching { download(path, dest) }.isSuccess) dest else null
        }

    private fun download(urlString: String, dest: File) {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            if (dest.length() == 0L) {
                dest.delete()
                throw IllegalStateException("Empty download")
            }
        } finally {
            conn.disconnect()
        }
    }
}
