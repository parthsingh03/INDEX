package com.parth.index.ui.subject

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.R
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.Roles
import com.parth.index.databinding.ActivitySubjectDetailBinding
import com.parth.index.ui.adapters.FileAdapter
import com.parth.index.ui.upload.UploadActivity
import com.parth.index.util.FileActions
import com.parth.index.util.FileSaver
import kotlinx.coroutines.launch

/** One subject folder: its files, each with the uploader's note and no
 *  comments anywhere. Long-press a file for actions; what you may do depends
 *  on who you are — your own files always, anyone's if you run the class.
 *  v1.4 adds the filter chips + sort control above the list (client-side,
 *  session-only) and a real Download on every row (see FileSaver). */
class SubjectDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubjectDetailBinding
    private val app get() = application as IndexApp
    private var subjectId: Long = -1

    private lateinit var adapter: FileAdapter
    private var allFiles: List<MaterialFileEntity> = emptyList()
    private var filterMode = FILTER_ALL
    private var sortMode = SORT_NEWEST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubjectDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        subjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)

        adapter = FileAdapter(
            subjectNames = { emptyMap() },
            onClick = { file -> FileActions.open(this, file) },
            onLongClick = { file -> showFileActions(file) },
            onDownload = { file -> FileSaver.save(this, file) }
        )
        binding.rvSubjectFiles.layoutManager = LinearLayoutManager(this)
        binding.rvSubjectFiles.adapter = adapter

        binding.btnBackSubject.setOnClickListener { finish() }
        binding.btnUploadFile.setOnClickListener {
            startActivity(UploadActivity.intent(this, subjectId))
        }

        binding.chipFilterAll.setOnClickListener { filterMode = FILTER_ALL; renderFiles() }
        binding.chipFilterPdf.setOnClickListener { filterMode = FILTER_PDF; renderFiles() }
        binding.chipFilterImg.setOnClickListener { filterMode = FILTER_IMAGE; renderFiles() }
        binding.tvSortMode.setOnClickListener { showSortPicker() }
        styleChips()
        styleSortLabel()

        lifecycleScope.launch {
            val subject = app.repository.subjectById(subjectId)
            binding.tvSubjectTitle.text = subject?.name ?: "Subject"
            app.repository.observeFilesForSubject(subjectId).collect { files ->
                allFiles = files
                renderFiles()
            }
        }
    }

    /** Apply the current filter + sort to the latest list and repaint. */
    private fun renderFiles() {
        val filtered = allFiles.filter { file ->
            when (filterMode) {
                FILTER_PDF -> FileActions.isPdf(file)
                FILTER_IMAGE -> FileActions.isImage(file)
                else -> true
            }
        }
        // Pinned files keep their head start under every sort (they were
        // pinned for a reason); the sort orders each group internally.
        val sorted = filtered.sortedWith { a, b ->
            if (a.pinned != b.pinned) {
                if (a.pinned) -1 else 1
            } else {
                when (sortMode) {
                    SORT_OLDEST -> a.createdAt.compareTo(b.createdAt)
                    SORT_BIGGEST -> b.sizeBytes.compareTo(a.sizeBytes)
                    else -> b.createdAt.compareTo(a.createdAt)
                }
            }
        }
        binding.tvSubjectMeta.text =
            "${allFiles.size} file${if (allFiles.size == 1) "" else "s"} in this folder"
        adapter.submit(sorted)
        binding.tvSubjectEmpty.visibility = when {
            sorted.isNotEmpty() -> View.GONE
            allFiles.isEmpty() -> View.VISIBLE
            else -> View.VISIBLE
        }
        binding.tvSubjectEmpty.text =
            if (allFiles.isEmpty()) "No material in this subject yet."
            else "Nothing in this folder matches that filter."
        styleChips()
    }

    private fun styleChips() {
        styleChip(binding.chipFilterAll, filterMode == FILTER_ALL)
        styleChip(binding.chipFilterPdf, filterMode == FILTER_PDF)
        styleChip(binding.chipFilterImg, filterMode == FILTER_IMAGE)
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_segment_selected)
            chip.setTextColor(Color.WHITE)
        } else {
            chip.setBackgroundResource(R.drawable.bg_segment)
            chip.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun styleSortLabel() {
        binding.tvSortMode.text = when (sortMode) {
            SORT_OLDEST -> "Sort: Oldest ▾"
            SORT_BIGGEST -> "Sort: Biggest ▾"
            else -> "Sort: Newest ▾"
        }
    }

    private fun showSortPicker() {
        val labels = arrayOf("Newest first", "Oldest first", "Biggest first")
        AlertDialog.Builder(this)
            .setTitle("Sort files")
            .setSingleChoiceItems(labels, sortMode) { dialog, which ->
                sortMode = which
                styleSortLabel()
                renderFiles()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileActions(file: MaterialFileEntity) {
        val prefs = app.prefs
        val mine = file.uploaderName == prefs.userName
        val canManage = Roles.canManage(prefs.role)
        val actions = mutableListOf<String>()
        actions.add("Open / preview")
        actions.add(if (file.pinned) "Unpin" else "Pin")
        if (mine) actions.add("Edit my note")
        if (file.localPath.isNotEmpty()) actions.add("Download a copy")
        if (mine || canManage) actions.add("Delete (to Trash)")
        AlertDialog.Builder(this)
            .setTitle(file.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Open / preview" -> FileActions.open(this, file)
                    "Pin" -> lifecycleScope.launch {
                        app.repository.updateFile(file.copy(pinned = true))
                    }
                    "Unpin" -> lifecycleScope.launch {
                        app.repository.updateFile(file.copy(pinned = false))
                    }
                    "Edit my note" -> showEditNote(file)
                    "Download a copy" -> FileSaver.save(this, file)
                    "Delete (to Trash)" -> lifecycleScope.launch {
                        app.repository.deleteFile(file.id)
                        Toast.makeText(this@SubjectDetailActivity, "Moved to Trash.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** Only the uploader ever sees this. A note is a caption by its owner,
     *  not a comment thread — nobody else can write on a file. */
    private fun showEditNote(file: MaterialFileEntity) {
        val et = EditText(this).apply {
            setText(file.note)
            hint = "Your note on this file"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Your note")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    app.repository.updateFile(file.copy(note = et.text.toString().trim()))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val EXTRA_SUBJECT_ID = "subject_id"

        private const val FILTER_ALL = 0
        private const val FILTER_PDF = 1
        private const val FILTER_IMAGE = 2

        private const val SORT_NEWEST = 0
        private const val SORT_OLDEST = 1
        private const val SORT_BIGGEST = 2

        fun intent(context: Context, subjectId: Long): Intent =
            Intent(context, SubjectDetailActivity::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
    }
}
