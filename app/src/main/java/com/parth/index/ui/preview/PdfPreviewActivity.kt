package com.parth.index.ui.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.databinding.ActivityPdfPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Built-in PDF preview. PdfRenderer renders ONE page at a time and the
 *  bitmap is recycled the moment you move on — a 200-page PDF costs the
 *  same memory as a 1-page PDF. That is the whole trick, and it is why
 *  preview stays smooth on small phones. */
class PdfPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfPreviewBinding
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var currentPage = 0
    private var pageCount = 0
    private var currentBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvPdfTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "The copy on this phone is missing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(fd)
                    descriptor = fd
                    renderer = r
                    r.pageCount
                }
            }
            opened.onSuccess { count ->
                pageCount = count
                if (count > 0) showPage(0) else {
                    binding.tvPageInfo.text = "This PDF has no pages."
                }
            }.onFailure {
                binding.tvPageInfo.text = "Could not open this PDF."
            }
        }

        binding.btnPrevPage.setOnClickListener { if (currentPage > 0) showPage(currentPage - 1) }
        binding.btnNextPage.setOnClickListener {
            if (currentPage < pageCount - 1) showPage(currentPage + 1)
        }
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        currentPage = index
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    r.openPage(index).use { page ->
                        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.getOrNull()
            }
            currentBitmap?.recycle()
            currentBitmap = bitmap
            binding.ivPdfPage.setImageBitmap(bitmap)
            binding.tvPageInfo.text = "Page ${index + 1} of $pageCount"
            binding.btnPrevPage.isEnabled = index > 0
            binding.btnNextPage.isEnabled = index < pageCount - 1
        }
    }

    override fun onDestroy() {
        currentBitmap?.recycle()
        currentBitmap = null
        renderer?.close()
        descriptor?.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PATH = "path"

        fun intent(context: Context, fileId: Long, title: String, localPath: String): Intent =
            Intent(context, PdfPreviewActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, fileId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PATH, localPath)
            }
    }
}
