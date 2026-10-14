package com.parth.index.ui.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.IndexApp
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.SubjectEntity
import com.parth.index.databinding.FragmentPdfMakerBinding
import com.parth.index.databinding.ItemPdfPageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** PDF Maker: photos of pages in, one clean PDF out — no ads, no watermark.
 *
 *  The low-RAM rule that makes this work on small phones: pages are built
 *  ONE at a time. Each photo is decoded already shrunk (never at full
 *  camera resolution), drawn onto its page, and recycled before the next
 *  photo is touched. Ten full-size photos held in memory at once is how
 *  scanner apps crash budget phones; that never happens here. All of it
 *  runs on the IO dispatcher, so the screen never freezes. */
class PdfMakerFragment : Fragment() {

    private var _binding: FragmentPdfMakerBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as IndexApp

    private val pages = mutableListOf<Uri>()
    private lateinit var pageAdapter: PageAdapter
    private var subjects: List<SubjectEntity> = emptyList()
    private var pendingDownload = false

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pages.addAll(uris)
            pageAdapter.notifyDataSetChanged()
            refreshEmptyState()
        }
    }

    private val createPdfDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null && pendingDownload) {
            pendingDownload = false
            buildPdf { requireContext().contentResolver.openOutputStream(uri) }
        } else {
            pendingDownload = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPdfMakerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pageAdapter = PageAdapter(
            items = pages,
            resolver = { requireContext().contentResolver },
            onMove = { from, to ->
                val item = pages.removeAt(from)
                pages.add(to, item)
                pageAdapter.notifyDataSetChanged()
            },
            onRemove = { pos ->
                pages.removeAt(pos)
                pageAdapter.notifyDataSetChanged()
                refreshEmptyState()
            }
        )
        binding.rvPdfPages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPdfPages.adapter = pageAdapter

        binding.btnPickImages.setOnClickListener {
            pickImages.launch(arrayOf("image/*"))
        }
        binding.btnMakePdfSave.setOnClickListener {
            if (!validatePages()) return@setOnClickListener
            val pos = binding.spinnerPdfSubject.selectedItemPosition
            if (pos < 0 || pos >= subjects.size) {
                Toast.makeText(requireContext(), "No subject to save into.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val subject = subjects[pos]
            buildPdfToSubject(subject)
        }
        binding.btnMakePdfDownload.setOnClickListener {
            if (!validatePages()) return@setOnClickListener
            pendingDownload = true
            createPdfDocument.launch(pdfTitle() + ".pdf")
        }

        lifecycleScope.launch {
            app.repository.observeSubjects().collect { list ->
                subjects = list
                binding.spinnerPdfSubject.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    list.map { it.name }
                )
            }
        }
        refreshEmptyState()
    }

    private fun validatePages(): Boolean {
        if (pages.isEmpty()) {
            Toast.makeText(requireContext(), "Pick at least one photo first.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun pdfTitle(): String =
        binding.etPdfTitle.text.toString().trim().ifEmpty { "Assignment" }

    private fun refreshEmptyState() {
        binding.tvPdfEmpty.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildPdfToSubject(subject: SubjectEntity) {
        val title = pdfTitle()
        lifecycleScope.launch {
            val dest = File(
                File(requireContext().filesDir, "materials").apply { mkdirs() },
                "${System.currentTimeMillis()}_${title.replace(Regex("[^A-Za-z0-9._-]"), "_")}.pdf"
            )
            val ok = buildPdfInternal { dest.outputStream() }
            if (ok) {
                val entity = MaterialFileEntity(
                    subjectId = subject.id,
                    title = if (title.endsWith(".pdf", true)) title else "$title.pdf",
                    note = binding.etPdfNote.text.toString().trim(),
                    tags = "pdf maker",
                    fileName = dest.name,
                    mimeType = "application/pdf",
                    sizeBytes = dest.length(),
                    localPath = dest.absolutePath,
                    uploaderName = app.prefs.userName,
                    uploaderRole = app.prefs.role
                )
                val id = app.repository.addFile(entity)
                app.repository.postUploadNotice(entity.copy(id = id), subject.name)
                Toast.makeText(
                    requireContext(),
                    "PDF saved to ${subject.name}.",
                    Toast.LENGTH_LONG
                ).show()
                pages.clear()
                pageAdapter.notifyDataSetChanged()
                refreshEmptyState()
            }
        }
    }

    private fun buildPdf(openStream: () -> OutputStream?) {
        lifecycleScope.launch { buildPdfInternal(openStream) }
    }

    /** Builds the PDF page by page on the IO thread. Returns success. */
    private suspend fun buildPdfInternal(openStream: () -> OutputStream?): Boolean {
        val uris = pages.toList()
        if (uris.isEmpty()) return false
        binding.progressPdf.visibility = View.VISIBLE
        binding.progressPdf.max = uris.size
        binding.progressPdf.progress = 0
        binding.btnMakePdfSave.isEnabled = false
        binding.btnMakePdfDownload.isEnabled = false
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val stream = openStream() ?: error("Could not open output")
                stream.use { out ->
                    val document = PdfDocument()
                    try {
                        val pageWidth = 1240
                        val pageHeight = 1754
                        val paint = Paint()
                        uris.forEachIndexed { index, uri ->
                            val bitmap = decodeDownscaled(uri, pageWidth, pageHeight)
                                ?: error("Could not read one of the photos")
                            try {
                                val pageInfo = PdfDocument.PageInfo
                                    .Builder(pageWidth, pageHeight, index + 1).create()
                                val page = document.startPage(pageInfo)
                                val canvas: Canvas = page.canvas
                                canvas.drawColor(Color.WHITE)
                                val dest = fitRect(bitmap.width, bitmap.height, pageWidth, pageHeight)
                                canvas.drawBitmap(bitmap, null, dest, paint)
                                document.finishPage(page)
                            } finally {
                                bitmap.recycle()
                            }
                            withContext(Dispatchers.Main) {
                                binding.progressPdf.progress = index + 1
                                binding.tvPdfStatus.text = "Page ${index + 1} of ${uris.size}…"
                            }
                        }
                        document.writeTo(out)
                    } finally {
                        document.close()
                    }
                }
            }
        }
        binding.progressPdf.visibility = View.GONE
        binding.btnMakePdfSave.isEnabled = true
        binding.btnMakePdfDownload.isEnabled = true
        binding.tvPdfStatus.text =
            if (result.isSuccess) "Done — ${uris.size} page${if (uris.size == 1) "" else "s"}."
            else "Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
        if (result.isFailure) {
            Toast.makeText(requireContext(), "PDF making failed.", Toast.LENGTH_SHORT).show()
        }
        return result.isSuccess
    }

    /** Decodes a photo already shrunk to at most [maxW]x[maxH]. The bounds
     *  pass reads only the photo's header; the sample size then skips pixels
     *  while decoding, so a 48 MP camera photo never lands in memory whole. */
    private fun decodeDownscaled(uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        val resolver = requireContext().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun fitRect(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Rect {
        val scale = minOf(boxW.toFloat() / srcW, boxH.toFloat() / srcH)
        val w = (srcW * scale).toInt()
        val h = (srcH * scale).toInt()
        val left = (boxW - w) / 2
        val top = (boxH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Reorderable page list with small sampled thumbnails. */
    class PageAdapter(
        private val items: List<Uri>,
        private val resolver: () -> android.content.ContentResolver,
        private val onMove: (Int, Int) -> Unit,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemPdfPageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val uri = items[position]
            with(holder.binding) {
                tvPageName.text = "Page ${position + 1}"
                btnPageUp.isEnabled = position > 0
                btnPageDown.isEnabled = position < items.size - 1
                btnPageUp.setOnClickListener { onMove(position, position - 1) }
                btnPageDown.setOnClickListener { onMove(position, position + 1) }
                btnPageRemove.setOnClickListener { onRemove(position) }
                ivPageThumb.setImageBitmap(null)
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inSampleSize = 8 }
                    resolver().openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                    }?.let { ivPageThumb.setImageBitmap(it) }
                }
            }
        }

        class Holder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
