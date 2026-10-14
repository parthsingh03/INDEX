package com.parth.index.ui.preview

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.databinding.ActivityImageViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Full-screen image view. The photo is decoded shrunk to the screen size
 *  (never at full camera resolution), so even a 48 MP photo opens fast and
 *  light. */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private val app get() = application as IndexApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvImageTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Photo"
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1)

        lifecycleScope.launch {
            val file = app.repository.fileById(fileId)
            val path = file?.localPath.orEmpty()
            if (path.isEmpty() || !File(path).exists()) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    "This is a sample entry — upload a real photo to view it here.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    FileInputStream(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                    val target = 1440
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= target ||
                        bounds.outHeight / (sample * 2) >= target
                    ) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    FileInputStream(path).use { BitmapFactory.decodeStream(it, null, opts) }
                }.getOrNull()
            }
            if (bitmap != null) binding.ivFullImage.setImageBitmap(bitmap)
            else Toast.makeText(this@ImageViewerActivity, "Could not open this image.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, fileId: Long, title: String): Intent =
            Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, fileId)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
