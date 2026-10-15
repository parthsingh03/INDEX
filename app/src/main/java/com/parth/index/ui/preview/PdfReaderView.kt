package com.parth.index.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Continuous PDF reader: every page stacked in ONE vertical scroll —
 *  no Prev/Next tapping through a 300-page document. Pinch (or
 *  double-tap) to zoom up to [MAX_ZOOM]x, drag to pan, fling to fly,
 *  and a scrollbar thumb on the right edge that can be dragged to jump
 *  through the document.
 *
 *  Memory stays flat no matter the page count: pages are rendered
 *  lazily, only when they approach the viewport, into an LruCache —
 *  the same one-page-at-a-time trick as the old viewer, hidden inside
 *  a continuous scroll. ALL PdfRenderer access is serialized on one
 *  background thread (PdfRenderer is not thread-safe). */
class PdfReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Fired whenever the page at the centre of the viewport changes. */
    var onCurrentPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null

    /** Fired on a plain single tap (not a scroll, pinch or double-tap) —
     *  the fullscreen reader uses it to show/hide its chrome overlays. */
    var onTapAction: (() -> Unit)? = null

    private val renderDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(renderDispatcher)
    private var openJob: Job? = null

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /** (width, height) in PDF points per page, read once at open. */
    private var pageSizes: List<Pair<Int, Int>> = emptyList()

    /** Top edge of each page in content coordinates (zoom = 1 space). */
    private var pageTops: FloatArray = FloatArray(0)
    private var contentHeight = 0f

    private var scrollX = 0f
    private var scrollY = 0f
    private var zoom = 1f

    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val inFlight = HashSet<Long>()
    private var generation = 0

    private val scroller = OverScroller(context)
    private var draggingScrollbar = false

    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 140, 140, 140)
        style = Paint.Style.FILL
    }

    private val density = resources.displayMetrics.density
    private val gapPx = 10f * density
    private val scrollbarStripPx = 28f * density

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Sharper re-render at the settled zoom level.
                generation++
                postInvalidateOnAnimation()
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTapAction?.invoke()
                return false
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (!draggingScrollbar) {
                    scrollX += distanceX / zoom
                    scrollY += distanceY / zoom
                    clampScroll()
                    postInvalidateOnAnimation()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (draggingScrollbar) return false
                scroller.fling(
                    scrollX.roundToInt(), scrollY.roundToInt(),
                    (-velocityX / zoom).roundToInt(), (-velocityY / zoom).roundToInt(),
                    0, maxScrollX().roundToInt(), 0, maxScrollY().roundToInt()
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.01f) setZoom(1f, e.x, e.y)
                else setZoom(DOUBLE_TAP_ZOOM, e.x, e.y)
                generation++
                return true
            }
        })

    /** Open [file]; the callback reports total pages (0 on failure). */
    fun openPdf(file: File, onOpened: (pageCount: Int) -> Unit) {
        openJob = scope.launch {
            val count = runCatching {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(fd)
                val sizes = ArrayList<Pair<Int, Int>>(r.pageCount)
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { page -> sizes.add(page.width to page.height) }
                }
                descriptor = fd
                renderer = r
                pageSizes = sizes
                r.pageCount
            }.getOrDefault(0)
            post {
                layoutPages()
                onOpened(count)
                invalidate()
            }
        }
    }

    fun close() {
        generation++
        openJob?.cancel()
        scope.launch {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPages()
    }

    private fun layoutPages() {
        if (pageSizes.isEmpty() || width == 0) return
        val tops = FloatArray(pageSizes.size)
        var y = 0f
        for (i in pageSizes.indices) {
            tops[i] = y
            y += pageHeight(i) + gapPx
        }
        pageTops = tops
        contentHeight = if (pageSizes.isEmpty()) 0f else y - gapPx
        clampScroll()
    }

    private fun pageHeight(index: Int): Float {
        val (pw, ph) = pageSizes[index]
        if (pw == 0) return 0f
        return width.toFloat() * ph.toFloat() / pw.toFloat()
    }

    private fun maxScrollX(): Float = max(0f, width - width / zoom)
    private fun maxScrollY(): Float = max(0f, contentHeight - height / zoom)

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = scrollY.coerceIn(0f, maxScrollY())
    }

    private fun setZoom(newZoom: Float, focusX: Float, focusY: Float) {
        val clamped = newZoom.coerceIn(1f, MAX_ZOOM)
        if (clamped == zoom) return
        // Keep the content point under the fingers exactly where it is.
        val contentX = scrollX + focusX / zoom
        val contentY = scrollY + focusY / zoom
        zoom = clamped
        scrollX = contentX - focusX / zoom
        scrollY = contentY - focusY / zoom
        clampScroll()
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingScrollbar = canScroll() &&
                    event.x > width - scrollbarStripPx && event.pointerCount == 1
                if (draggingScrollbar) jumpToScrollbar(event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingScrollbar && event.pointerCount == 1) jumpToScrollbar(event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> draggingScrollbar = false
        }
        scaleDetector.onTouchEvent(event)
        if (!draggingScrollbar) gestureDetector.onTouchEvent(event)
        return true
    }

    private fun canScroll(): Boolean = contentHeight * zoom > height

    private fun jumpToScrollbar(touchY: Float) {
        val thumbH = scrollbarThumbHeight()
        val top = (touchY - thumbH / 2f).coerceIn(0f, max(1f, height - thumbH))
        val fraction = if (height - thumbH <= 0f) 0f else top / (height - thumbH)
        scrollY = fraction * maxScrollY()
        clampScroll()
        postInvalidateOnAnimation()
    }

    private fun scrollbarThumbHeight(): Float =
        max(48f * density, height * (height / (contentHeight * zoom)))

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat()
            scrollY = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    private var lastReportedPage = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageSizes.isEmpty() || width == 0) return

        canvas.save()
        canvas.scale(zoom, zoom)
        canvas.translate(-scrollX, -scrollY)

        val viewTop = scrollY
        val viewBottom = scrollY + height / zoom

        var firstVisible = -1
        var lastVisible = -1
        for (i in pageSizes.indices) {
            val top = pageTops[i]
            val bottom = top + pageHeight(i)
            if (bottom < viewTop - PRELOAD_PX / zoom || top > viewBottom + PRELOAD_PX / zoom) continue
            if (firstVisible < 0) firstVisible = i
            lastVisible = i
            val rect = RectF(0f, top, width.toFloat(), bottom)
            val bmp = cache.get(cacheKey(i))
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, rect, null)
            } else {
                canvas.drawRect(rect, placeholderPaint)
                requestRender(i)
            }
        }
        canvas.restore()

        // Page indicator follows the page under the viewport centre.
        val centre = scrollY + (height / zoom) / 2f
        var current = pageSizes.lastIndex
        for (i in pageSizes.indices) {
            if (centre < pageTops[i] + pageHeight(i) + gapPx) {
                current = i
                break
            }
        }
        if (current != lastReportedPage && firstVisible >= 0) {
            lastReportedPage = current
            onCurrentPageChanged?.invoke(current + 1, pageSizes.size)
        }

        drawScrollbar(canvas)
    }

    private fun drawScrollbar(canvas: Canvas) {
        if (!canScroll()) return
        val thumbH = scrollbarThumbHeight()
        val maxTop = height - thumbH
        val top = if (maxScrollY() <= 0f) 0f else (scrollY / maxScrollY()) * maxTop
        val w = 4f * density
        canvas.drawRoundRect(
            RectF(width - w - 2f * density, top, width - 2f * density, top + thumbH),
            w / 2f, w / 2f, scrollbarPaint
        )
    }

    private fun zoomBucket(): Float = (zoom * 2f).roundToInt() / 2f

    private fun cacheKey(page: Int): Long =
        page.toLong() * 1000L + (zoomBucket() * 10f).roundToInt()

    private fun requestRender(page: Int) {
        val key = cacheKey(page)
        synchronized(inFlight) {
            if (!inFlight.add(key)) return
        }
        val gen = generation
        val bucket = zoomBucket()
        scope.launch {
            val bmp = runCatching { renderPage(page, bucket) }.getOrNull()
            synchronized(inFlight) { inFlight.remove(key) }
            if (bmp != null && gen == generation) {
                cache.put(key, bmp)
                postInvalidateOnAnimation()
            } else {
                bmp?.recycle()
            }
        }
    }

    /** Runs ONLY on the single render thread — PdfRenderer stays safe. */
    private fun renderPage(page: Int, bucket: Float): Bitmap? {
        val r = renderer ?: return null
        val (pw, ph) = pageSizes.getOrNull(page) ?: return null
        if (pw <= 0 || ph <= 0) return null
        val displayWidth = width
        if (displayWidth <= 0) return null
        val targetW = min((displayWidth * bucket).roundToInt(), MAX_RENDER_WIDTH)
        val targetH = (targetW.toFloat() * ph / pw).roundToInt().coerceAtLeast(1)
        return r.openPage(page).use { pdfPage ->
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    companion object {
        private const val MAX_ZOOM = 4f
        private const val DOUBLE_TAP_ZOOM = 2.2f
        private const val MAX_RENDER_WIDTH = 1400
        private const val PRELOAD_PX = 800f
    }
}
