package com.parth.index.util

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.parth.index.R
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.ui.preview.ImageViewerActivity
import com.parth.index.ui.preview.PdfPreviewActivity
import java.io.File

/** What happens when any file row anywhere in the app is tapped: images open
 *  in the built-in viewer, PDFs in the built-in one-page-at-a-time preview,
 *  everything else is handed to another app through FileProvider. */
object FileActions {

    /** Short badge text + colour for a file, from its type. */
    fun badge(file: MaterialFileEntity): Pair<String, Int> {
        val name = file.fileName.lowercase()
        return when {
            file.mimeType == "application/pdf" || name.endsWith(".pdf") ->
                "PDF" to 0xFFD63031.toInt()
            file.mimeType.startsWith("image/") ||
                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".png") || name.endsWith(".webp") ->
                "IMG" to 0xFF0D9488.toInt()
            name.endsWith(".doc") || name.endsWith(".docx") -> "DOC" to 0xFF2563EB.toInt()
            name.endsWith(".ppt") || name.endsWith(".pptx") -> "PPT" to 0xFFE17055.toInt()
            name.endsWith(".xls") || name.endsWith(".xlsx") -> "XLS" to 0xFF00B894.toInt()
            name.endsWith(".zip") -> "ZIP" to 0xFF6C5CE7.toInt()
            name.endsWith(".txt") -> "TXT" to 0xFF636E72.toInt()
            else -> "FILE" to 0xFF636E72.toInt()
        }
    }

    fun styleBadge(view: TextView, file: MaterialFileEntity) {
        val (label, color) = badge(file)
        view.text = label
        view.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    fun isImage(file: MaterialFileEntity): Boolean = badge(file).first == "IMG"
    fun isPdf(file: MaterialFileEntity): Boolean = badge(file).first == "PDF"

    fun open(activity: Activity, file: MaterialFileEntity) {
        if (file.localPath.isEmpty()) {
            Toast.makeText(
                activity,
                "This is a sample entry. Upload a real file and it will open right here.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val local = File(file.localPath)
        if (!local.exists()) {
            Toast.makeText(activity, "The copy on this phone is missing.", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            isImage(file) -> activity.startActivity(
                ImageViewerActivity.intent(activity, file.id, file.title)
            )
            isPdf(file) -> activity.startActivity(
                PdfPreviewActivity.intent(activity, file.id, file.title, file.localPath)
            )
            else -> {
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        activity, activity.packageName + ".fileprovider", local
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, file.mimeType.ifEmpty { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "Open with"))
                }.onFailure {
                    Toast.makeText(activity, "No app on this phone can open that file type.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Colour for text drawn on the app surface (used by a couple of rows). */
    fun surfaceColor(activity: Activity): Int =
        ContextCompat.getColor(activity, R.color.on_surface)
}
