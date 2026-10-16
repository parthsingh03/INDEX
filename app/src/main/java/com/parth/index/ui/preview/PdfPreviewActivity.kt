package com.parth.index.ui.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.databinding.ActivityPdfPreviewBinding
import com.parth.index.util.FileSaver
import kotlinx.coroutines.launch
import java.io.File

/** Built-in PDF reader, TRUE fullscreen (v1.4). All pages live in ONE
 *  continuous scroll (PdfReaderView): drag or fling to move through the
 *  whole document, drag the scrollbar thumb to jump, pinch or double-tap
 *  to zoom. The system bars are hidden and the pages run edge-to-edge;
 *  the title, page indicator and download button are translucent
 *  overlays that fade out after a couple of seconds and return on tap.
 *  Pages render lazily around the viewport, so a 300-page PDF costs the
 *  same memory as a 3-page one. */
class PdfPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfPreviewBinding
    private val app get() = application as IndexApp

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeOut = Runnable { setOverlaysVisible(false) }
    private var overlaysShown = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()

        binding.tvPdfTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1)
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
        binding.readerView.onTapAction = { setOverlaysVisible(!overlaysShown) }
        binding.readerView.openPdf(file) { count ->
            if (count <= 0) {
                binding.tvPageInfo.text = "Could not open this PDF."
            }
        }
        binding.btnPdfRotate.setOnClickListener {
            setOverlaysVisible(true)
            binding.readerView.rotateClockwise()
        }
        binding.btnPdfDownload.setOnClickListener {
            setOverlaysVisible(true)
            if (fileId > 0) {
                lifecycleScope.launch {
                    app.repository.fileById(fileId)?.let { record ->
                        FileSaver.save(this@PdfPreviewActivity, record)
                    }
                }
            }
        }
        setOverlaysVisible(true)
    }

    /** Immersive mode: no status bar, no navigation bar — a swipe from
     *  the edge brings them back briefly (transient), nothing more. */
    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setOverlaysVisible(show: Boolean) {
        overlaysShown = show
        fadeHandler.removeCallbacks(fadeOut)
        val targetAlpha = if (show) 1f else 0f
        for (overlay in listOf(binding.overlayPdfTop, binding.tvPageInfo)) {
            overlay.animate().cancel()
            if (show) overlay.visibility = View.VISIBLE
            overlay.animate()
                .alpha(targetAlpha)
                .setDuration(250)
                .withEndAction {
                    if (!overlaysShown) overlay.visibility = View.GONE
                }
                .start()
        }
        if (show) fadeHandler.postDelayed(fadeOut, OVERLAY_TIMEOUT_MS)
    }

    override fun onDestroy() {
        fadeHandler.removeCallbacks(fadeOut)
        binding.readerView.close()
        super.onDestroy()
    }

    companion object {
        private const val OVERLAY_TIMEOUT_MS = 2500L
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
