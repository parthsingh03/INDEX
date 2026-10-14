package com.parth.index.ui.upload

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.SubjectEntity
import com.parth.index.databinding.ActivityUploadBinding
import com.parth.index.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The one upload screen for every route in:
 *   - the + Upload button inside a subject,
 *   - fulfilling a request,
 *   - the PDF maker's output (it saves directly, not through here),
 *   - and the share sheet: a file shared from WhatsApp/Files/Gallery
 *     arrives already attached, its name pre-filled as the title.
 *  Whatever the route, saving means: copy the file into INDEX's private
 *  storage on a background thread, write the row, and auto-post the
 *  "X added … to …" message in group chat. */
class UploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUploadBinding
    private val app get() = application as IndexApp

    private var subjects: List<SubjectEntity> = emptyList()
    private var pickedUri: Uri? = null
    private var pickedName: String = ""
    private var pickedSize: Long = 0
    private var pickedMime: String = ""
    private var preselectedSubjectId: Long = -1
    private var requestId: Long = -1

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) acceptUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preselectedSubjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1)

        if (requestId > 0) {
            binding.tvUploadHeading.text = "Fulfil the request"
            binding.tvFulfilInfo.visibility = View.VISIBLE
            lifecycleScope.launch {
                app.repository.requestById(requestId)?.let { req ->
                    binding.tvFulfilInfo.text =
                        "Requested by ${req.requesterName}: “${req.title}”. " +
                            "Uploading here closes the request."
                    if (preselectedSubjectId <= 0) preselectedSubjectId = req.subjectId
                }
            }
        }

        // Shared file(s) arrive here. For SEND_MULTIPLE we take every file
        // in turn: the first fills this screen; the rest are queued simply
        // by re-launching after save (kept simple and reliable).
        val sharedUris = extractSharedUris(intent)
        if (sharedUris.isNotEmpty()) acceptUri(sharedUris.first())

        binding.btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("application/pdf", "image/*", "application/*", "text/*"))
        }
        binding.btnDoUpload.setOnClickListener { startUpload() }

        lifecycleScope.launch {
            app.repository.observeSubjects().collect { list ->
                subjects = list
                val adapter = ArrayAdapter(
                    this@UploadActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    list.map { it.name }
                )
                binding.spinnerUploadSubject.adapter = adapter
                val idx = list.indexOfFirst { it.id == preselectedSubjectId }
                if (idx >= 0) binding.spinnerUploadSubject.setSelection(idx)
            }
        }
    }

    private fun extractSharedUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { out.add(it) }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (out.isEmpty() && text != null) {
                    binding.etUploadNote.setText(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            }
        }
        return out
    }

    private fun acceptUri(uri: Uri) {
        pickedUri = uri
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { queryMeta(uri) }
            pickedName = meta.first
            pickedSize = meta.second
            pickedMime = contentResolver.getType(uri) ?: mimeFromName(pickedName)
            binding.tvPickedFile.text = "Picked: $pickedName (${Format.size(pickedSize)})"
            if (binding.etUploadTitle.text.isNullOrEmpty()) {
                binding.etUploadTitle.setText(pickedName.substringBeforeLast('.', pickedName))
            }
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = "shared-file"
        var size = 0L
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = it.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = it.getLong(sizeIdx)
            }
        }
        if (size == 0L) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var read: Int
                    while (input.read(buf).also { r -> read = r } > 0) total += read
                    size = total
                }
            }
        }
        return name to size
    }

    private fun mimeFromName(name: String): String = when (name.lowercase().substringAfterLast('.', "")) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun startUpload() {
        val uri = pickedUri
        if (uri == null) {
            Toast.makeText(this, "Pick a file first.", Toast.LENGTH_SHORT).show()
            return
        }
        val subjectPos = binding.spinnerUploadSubject.selectedItemPosition
        if (subjectPos < 0 || subjectPos >= subjects.size) {
            Toast.makeText(this, "No subject to save into yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val subject = subjects[subjectPos]
        lifecycleScope.launch {
            val dups = app.repository.findDuplicates(subject.id, pickedName, pickedSize)
            if (dups.isNotEmpty()) {
                AlertDialog.Builder(this@UploadActivity)
                    .setTitle("Already in ${subject.name}?")
                    .setMessage(
                        "A file with the same name and size is already here: " +
                            "“${dups.first().title}”, uploaded by ${dups.first().uploaderName}. " +
                            "Upload another copy anyway?"
                    )
                    .setPositiveButton("Upload anyway") { _, _ -> doSave(uri, subject) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                doSave(uri, subject)
            }
        }
    }

    private fun doSave(uri: Uri, subject: SubjectEntity) {
        binding.progressUpload.visibility = View.VISIBLE
        binding.btnDoUpload.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                val dest = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "materials").apply { mkdirs() }
                    val safeName = pickedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(dir, "${System.currentTimeMillis()}_$safeName")
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: error("Could not read the picked file")
                    out
                }
                val title = binding.etUploadTitle.text.toString().trim()
                    .ifEmpty { pickedName }
                val entity = MaterialFileEntity(
                    subjectId = subject.id,
                    title = title,
                    note = binding.etUploadNote.text.toString().trim(),
                    tags = binding.etUploadTags.text.toString().trim(),
                    fileName = pickedName,
                    mimeType = pickedMime,
                    sizeBytes = if (pickedSize > 0) pickedSize else dest.length(),
                    localPath = dest.absolutePath,
                    uploaderName = app.prefs.userName,
                    uploaderRole = app.prefs.role,
                    requestId = if (requestId > 0) requestId else 0
                )
                val newId = app.repository.addFile(entity)
                val saved = entity.copy(id = newId)
                app.repository.postUploadNotice(saved, subject.name)
                if (requestId > 0) {
                    app.repository.fulfilRequest(requestId, newId, app.prefs.userName)
                }
                saved
            }
            binding.progressUpload.visibility = View.GONE
            binding.btnDoUpload.isEnabled = true
            result.onSuccess {
                Toast.makeText(
                    this@UploadActivity,
                    "Saved to ${subject.name}. The class can see it in chat too.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }.onFailure {
                Toast.makeText(
                    this@UploadActivity,
                    "Upload failed: ${it.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val EXTRA_SUBJECT_ID = "subject_id"
        private const val EXTRA_REQUEST_ID = "request_id"

        fun intent(context: Context, subjectId: Long = -1, requestId: Long = -1): Intent =
            Intent(context, UploadActivity::class.java).apply {
                putExtra(EXTRA_SUBJECT_ID, subjectId)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
    }
}
