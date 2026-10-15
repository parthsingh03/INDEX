package com.parth.index.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.data.model.MaterialFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Download" for every file, for every member (v1.4): the file leaves
 *  the app's private cache and becomes the member's own copy in the
 *  phone's Downloads/INDEX folder, written through MediaStore — no
 *  storage permission, and photos land where gallery apps look.
 *  Before Android 10 there is no permission-free way to write to the
 *  shared Downloads folder, so the save declines with an honest toast
 *  instead of pretending. */
object FileSaver {

    /** Fire-and-forget entry point used by rows and both viewers. */
    fun save(activity: Activity, file: MaterialFileEntity) {
        val scope = (activity as? AppCompatActivity)?.lifecycleScope
        if (scope == null) {
            Toast.makeText(activity, "Cannot save this file here.", Toast.LENGTH_SHORT).show()
            return
        }
        if (file.localPath.isEmpty()) {
            Toast.makeText(
                activity,
                "This is a sample entry — there is no file to download yet.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        scope.launch {
            Toast.makeText(activity, "Downloading ${file.fileName}…", Toast.LENGTH_SHORT).show()
            val local = RemoteFiles.localCopy(activity, file)
            if (local == null) {
                Toast.makeText(
                    activity,
                    "Could not download this file — check the connection and try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val saved = withContext(Dispatchers.IO) { writeToDownloads(activity, local, file) }
            Toast.makeText(
                activity,
                if (saved) "Saved ${file.fileName} to Downloads"
                else "Could not save this file on this phone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun writeToDownloads(context: Context, local: File, file: MaterialFileEntity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val mime = file.mimeType.ifEmpty {
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.fileName.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/INDEX"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        return try {
            val opened = resolver.openOutputStream(uri)?.use { out ->
                local.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
            if (!opened) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
