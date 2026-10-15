package com.parth.index.ui.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.parth.index.databinding.ActivityPdfPreviewBinding
import java.io.File

/** Built-in PDF reader. All pages live in ONE continuous scroll
 *  (PdfReaderView): drag or fling to move through the whole document,
 *  drag the scrollbar thumb to jump, pinch or double-tap to zoom.
 *  Pages render lazily around the viewport, so a 300-page PDF costs
 *  the same memory as a 3-page one. */
class PdfPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfPreviewBinding

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

        binding.tvPageInfo.text = "Opening…"
        binding.readerView.onCurrentPageChanged = { page, count ->
            binding.tvPageInfo.text = "Page $page of $count"
        }
        binding.readerView.openPdf(file) { count ->
            if (count <= 0) {
                binding.tvPageInfo.text = "Could not open this PDF."
            }
        }
    }

    override fun onDestroy() {
        binding.readerView.close()
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
