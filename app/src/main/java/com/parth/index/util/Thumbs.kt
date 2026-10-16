package com.parth.index.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.ImageView
import com.parth.index.data.model.MaterialFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Real peeks for file rows (v1.4): a photo shows ITSELF, a PDF shows its
 *  actual first page — the coloured format box is only the fallback while
 *  a thumbnail loads (or when none can be made).
 *
 *  Sources, cheapest first:
 *   1. memory cache, then an on-disk cache in cacheDir/thumbs (keyed by
 *      file id, so a peek is fetched/rendered once per phone, ever);
 *   2. Cloudinary delivery tricks for remote files — files live there as
 *      image-type uploads, so a photo can be asked for at w_240 and a
 *      PDF's first page as pg_1 + .jpg, both tiny downloads;
 *   3. the bytes themselves: decode the local/downloaded copy at low
 *      resolution, or render PDF page 1 with PdfRenderer.
 *  All work happens on a small background pool; the ImageView's tag (the
 *  file id) guards against RecyclerView recycling races. */
object Thumbs {

    private const val TARGET_WIDTH = 240
    private const val MAX_FETCH_BYTES = 8 * 1024 * 1024

    private val memory = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Show [file]'s thumbnail in [view]; leaves the view empty (the
     *  fallback behind it shows through) when none can be produced. */
    fun loadInto(view: ImageView, file: MaterialFileEntity) {
        view.tag = file.id
        val cached = memory.get(file.id)
        if (cached != null && !cached.isRecycled) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(null)
        if (!FileActions.isImage(file) && !FileActions.isPdf(file)) return
        val appContext = view.context.applicationContext
        pool.execute {
            val bmp = runCatching { produce(appContext, file) }.getOrNull()
            if (bmp != null) {
                memory.put(file.id, bmp)
                main.post {
                    if (view.tag == file.id) view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun produce(context: Context, file: MaterialFileEntity): Bitmap? {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val diskFile = File(dir, "${file.id}.jpg")
        if (diskFile.exists() && diskFile.length() > 0) {
            decodeFile(diskFile)?.let { return it }
        }
        val bmp = when {
            FileActions.isImage(file) -> imageThumb(context, file)
            FileActions.isPdf(file) -> pdfThumb(context, file)
            else -> null
        } ?: return null
        runCatching {
            FileOutputStream(diskFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
        }
        return bmp
    }

    private fun imageThumb(context: Context, file: MaterialFileEntity): Bitmap? {
        if (!RemoteFiles.isRemote(file)) {
            val local = File(file.localPath)
            return if (local.exists()) decodeFile(local) else null
        }
        val url = file.localPath
        cloudinaryTransform(url, "w_$TARGET_WIDTH,c_limit")?.let { small ->
            fetchDecode(small)?.let { return it }
        }
        return fetchDecode(url)
    }

    private fun pdfThumb(context: Context, file: MaterialFileEntity): Bitmap? {
        if (RemoteFiles.isRemote(file)) {
            cloudinaryPdfPageOne(file.localPath)?.let { pageUrl ->
                fetchDecode(pageUrl)?.let { return it }
            }
            // The delivery trick failed (odd URL shape, raw resource):
            // fall back to the real bytes and render page 1 ourselves.
            val local = runBlocking { RemoteFiles.localCopy(context, file) }
            return local?.let { renderPdfFirstPage(it) }
        }
        val local = File(file.localPath)
        return if (local.exists()) renderPdfFirstPage(local) else null
    }

    private fun renderPdfFirstPage(pdf: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return@runCatching null
                renderer.openPage(0).use { page ->
                    if (page.width <= 0 || page.height <= 0) return@runCatching null
                    val scale = TARGET_WIDTH.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(TARGET_WIDTH, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    /** Insert a transformation right after "/upload/" (transformations
     *  legally sit before the v<version> segment). Null for non-Cloudinary
     *  URLs. */
    private fun cloudinaryTransform(url: String, transform: String): String? {
        val marker = "/upload/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val cut = idx + marker.length
        return url.substring(0, cut) + transform + "/" + url.substring(cut)
    }

    /** A PDF stored as a Cloudinary image resource serves page 1 as an
     *  image: pg_1 picks the page, the .jpg extension picks the format. */
    private fun cloudinaryPdfPageOne(url: String): String? {
        val transformed = cloudinaryTransform(url, "pg_1,w_$TARGET_WIDTH") ?: return null
        val noQuery = transformed.substringBefore('?')
        return if (noQuery.lowercase().endsWith(".pdf")) {
            noQuery.dropLast(4) + ".jpg"
        } else {
            noQuery + ".jpg"
        }
    }

    private fun fetchDecode(url: String): Bitmap? {
        val bytes = fetchBytes(url) ?: return null
        return decodeBytes(bytes)
    }

    private fun fetchBytes(urlString: String): ByteArray? = runCatching {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            if (conn.responseCode !in 200..299) return@runCatching null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_FETCH_BYTES) return@runCatching null
                }
            }
            out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun sampleSize(width: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_WIDTH) sample *= 2
        return sample
    }

    // ---- Notice photos (v1.5): plain URLs, not file entities -----------

    private const val NOTICE_TARGET_WIDTH = 900

    private val urlMemory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private fun urlKey(url: String): String =
        Integer.toHexString(url.hashCode()) + "_" + url.length

    /** Show a remote photo (a notice's attached picture) in [view] at
     *  card size. Same discipline as file thumbs: memory cache, disk
     *  cache, background pool, tag guard against recycling races. */
    fun loadUrlInto(view: ImageView, url: String) {
        view.tag = url
        val cached = urlMemory.get(url)
        if (cached != null && !cached.isRecycled) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(null)
        val appContext = view.context.applicationContext
        pool.execute {
            val bmp = runCatching { produceUrl(appContext, url) }.getOrNull()
            if (bmp != null) {
                urlMemory.put(url, bmp)
                main.post {
                    if (view.tag == url) view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun produceUrl(context: Context, url: String): Bitmap? {
        val dir = File(context.cacheDir, "url_thumbs").apply { mkdirs() }
        val diskFile = File(dir, urlKey(url) + ".jpg")
        if (diskFile.exists() && diskFile.length() > 0) {
            decodeFileSized(diskFile, NOTICE_TARGET_WIDTH)?.let { return it }
        }
        val small = cloudinaryTransform(url, "w_$NOTICE_TARGET_WIDTH,c_limit") ?: url
        val bytes = fetchBytes(small) ?: fetchBytes(url) ?: return null
        val bmp = decodeBytesSized(bytes, NOTICE_TARGET_WIDTH) ?: return null
        runCatching {
            FileOutputStream(diskFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }
        return bmp
    }

    /** Download [url] once into cacheDir/notice_photos and hand back the
     *  local file — the fullscreen viewer decodes from disk, exactly
     *  like a regular photo. Null on any failure. */
    suspend fun localFileForUrl(context: Context, url: String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "notice_photos").apply { mkdirs() }
                val dest = File(dir, urlKey(url) + ".jpg")
                if (dest.exists() && dest.length() > 0) return@runCatching dest
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    if (dest.length() == 0L) {
                        dest.delete()
                        null
                    } else {
                        dest
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    private fun decodeBytesSized(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decodeFileSized(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
