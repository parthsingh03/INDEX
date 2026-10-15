package com.parth.index.ui.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.databinding.ActivityImageViewerBinding
import com.parth.index.util.FileActions
import com.parth.index.util.FileSaver
import com.parth.index.util.RemoteFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.min

/** Full-screen photo view with pinch-to-zoom, drag-to-pan and
 *  double-tap zoom (hand-rolled on a Matrix — no image library, keeping
 *  the APK small). The photo is decoded at up to ~2048 px, big enough to
 *  zoom into, never at full camera resolution, so it still opens fast.
 *
 *  v1.4: the viewer is a GALLERY — it loads every photo of the same
 *  subject folder and a quick horizontal swipe (while not zoomed) walks
 *  to the previous/next one, zoom resetting per photo. Chrome (title,
 *  "3 / 12", download) is a fading overlay, like the PDF reader's. */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private val app get() = application as IndexApp

    private val matrix = Matrix()
    private val baseMatrix = Matrix()
    private var bitmapSize = RectF()
    private var zoomed = false

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    /** The folder's photos in list order, and where we are in it. */
    private var gallery: List<MaterialFileEntity> = emptyList()
    private var index = 0
    private val currentFile: MaterialFileEntity? get() = gallery.getOrNull(index)

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeOut = Runnable { setOverlayVisible(false) }
    private var overlayShown = true

    // Raw swipe bookkeeping (decided on ACTION_UP, detectors see every
    // event first so pinch/pan/double-tap are untouched).
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding.tvImageTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Photo"
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1)

        setUpZoom()
        binding.btnImageDownload.setOnClickListener {
            setOverlayVisible(true)
            currentFile?.let { FileSaver.save(this, it) }
        }
        setOverlayVisible(true)

        lifecycleScope.launch {
            val file = app.repository.fileById(fileId)
            if (file == null) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    "This is a sample entry — upload a real photo to view it here.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // Gallery = the photos of the same folder, in the same order
            // the folder list shows them. Falls back to just this photo.
            val siblings = runCatching {
                app.repository.observeFilesForSubject(file.subjectId).first()
                    .filter { FileActions.isImage(it) }
            }.getOrDefault(emptyList())
            gallery = if (siblings.any { it.id == file.id }) siblings else listOf(file)
            index = gallery.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
            loadCurrent()
        }
    }

    private fun loadCurrent() {
        val file = currentFile ?: return
        binding.tvImageTitle.text = file.title
        binding.tvImageCounter.text =
            if (gallery.size > 1) "${index + 1} / ${gallery.size}" else ""
        lifecycleScope.launch {
            // Shared photos carry a Cloudinary URL: resolve (and cache) a
            // local copy before decoding, exactly like local files.
            val local = RemoteFiles.localCopy(this@ImageViewerActivity, file)
            val path = local?.absolutePath.orEmpty()
            if (path.isEmpty()) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    if (file.localPath.isEmpty()) {
                        "This is a sample entry — upload a real photo to view it here."
                    } else {
                        "Could not download this photo — check the connection and try again."
                    },
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    FileInputStream(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                    val target = 2048
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
            // The user may have swiped on while this decoded.
            if (currentFile?.id != file.id) return@launch
            if (bitmap != null) showBitmap(bitmap)
            else Toast.makeText(this@ImageViewerActivity, "Could not open this image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveBy(delta: Int) {
        val next = index + delta
        if (next !in gallery.indices) return
        index = next
        loadCurrent()
    }

    private fun showBitmap(bitmap: Bitmap) {
        binding.ivFullImage.setImageBitmap(bitmap)
        bitmapSize = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        // The view may not be laid out yet at this point — fit once it is.
        binding.ivFullImage.post { fitToView() }
    }

    /** Scale the photo to fit the view, centred. This fitted state is the
     *  1x baseline every zoom is measured against. */
    private fun fitToView() {
        val viewW = binding.ivFullImage.width.toFloat()
        val viewH = binding.ivFullImage.height.toFloat()
        if (viewW == 0f || viewH == 0f || bitmapSize.width() == 0f) return
        val scale = min(viewW / bitmapSize.width(), viewH / bitmapSize.height())
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(
            (viewW - bitmapSize.width() * scale) / 2f,
            (viewH - bitmapSize.height() * scale) / 2f
        )
        matrix.set(baseMatrix)
        binding.ivFullImage.imageMatrix = matrix
        zoomed = false
    }

    /** Total zoom relative to the fitted size (1f = fitted). */
    private fun currentZoom(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        val base = FloatArray(9)
        baseMatrix.getValues(base)
        return if (base[Matrix.MSCALE_X] == 0f) 1f
        else values[Matrix.MSCALE_X] / base[Matrix.MSCALE_X]
    }

    /** Keep the photo from being dragged or pinched into empty space:
     *  an axis smaller than the view stays centred on it; a larger one
     *  keeps its edges at or beyond the view's edges. */
    private fun clampToBounds() {
        val viewW = binding.ivFullImage.width.toFloat()
        val viewH = binding.ivFullImage.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val rect = RectF(bitmapSize)
        matrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        dx = when {
            rect.width() <= viewW -> (viewW - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < viewW -> viewW - rect.right
            else -> 0f
        }
        dy = when {
            rect.height() <= viewH -> (viewH - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < viewH -> viewH - rect.bottom
            else -> 0f
        }
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    private fun setUpZoom() {
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val target = (currentZoom() * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                    val factor = target / currentZoom()
                    if (factor != 1f) {
                        matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                        clampToBounds()
                        binding.ivFullImage.imageMatrix = matrix
                        zoomed = currentZoom() > 1.01f
                    }
                    return true
                }
            })
        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    setOverlayVisible(!overlayShown)
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    distanceX: Float, distanceY: Float
                ): Boolean {
                    if (zoomed) {
                        matrix.postTranslate(-distanceX, -distanceY)
                        clampToBounds()
                        binding.ivFullImage.imageMatrix = matrix
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (zoomed) {
                        fitToView()
                    } else {
                        val factor = DOUBLE_TAP_ZOOM / currentZoom()
                        matrix.postScale(factor, factor, e.x, e.y)
                        clampToBounds()
                        binding.ivFullImage.imageMatrix = matrix
                        zoomed = true
                    }
                    return true
                }
            })
        binding.ivFullImage.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val quick = event.eventTime - downTime < SWIPE_MAX_MS
                    if (!zoomed && gallery.size > 1 && quick &&
                        abs(dx) > SWIPE_MIN_DP * resources.displayMetrics.density &&
                        abs(dx) > abs(dy) * 1.4f
                    ) {
                        moveBy(if (dx < 0) 1 else -1)
                    }
                }
            }
            true
        }
    }

    private fun setOverlayVisible(show: Boolean) {
        overlayShown = show
        fadeHandler.removeCallbacks(fadeOut)
        binding.overlayImageTop.animate().cancel()
        if (show) binding.overlayImageTop.visibility = View.VISIBLE
        binding.overlayImageTop.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(250)
            .withEndAction {
                if (!overlayShown) binding.overlayImageTop.visibility = View.GONE
            }
            .start()
        if (show) fadeHandler.postDelayed(fadeOut, OVERLAY_TIMEOUT_MS)
    }

    override fun onDestroy() {
        fadeHandler.removeCallbacks(fadeOut)
        super.onDestroy()
    }

    companion object {
        private const val MAX_ZOOM = 5f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val OVERLAY_TIMEOUT_MS = 2500L
        private const val SWIPE_MIN_DP = 80f
        private const val SWIPE_MAX_MS = 500L
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, fileId: Long, title: String): Intent =
            Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, fileId)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
