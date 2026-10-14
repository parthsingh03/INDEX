package com.parth.index.ui.subject

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.Roles
import com.parth.index.databinding.ActivitySubjectDetailBinding
import com.parth.index.ui.adapters.FileAdapter
import com.parth.index.ui.upload.UploadActivity
import com.parth.index.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One subject folder: its files, each with the uploader's note and no
 *  comments anywhere. Long-press a file for actions; what you may do depends
 *  on who you are — your own files always, anyone's if you run the class. */
class SubjectDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubjectDetailBinding
    private val app get() = application as IndexApp
    private var subjectId: Long = -1
    private var pendingDownload: MaterialFileEntity? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingDownload
        pendingDownload = null
        if (uri != null && file != null) {
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            File(file.localPath).inputStream().use { it.copyTo(out) }
                        }
                    }
                }.onSuccess {
                    Toast.makeText(this@SubjectDetailActivity, "Copy saved.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@SubjectDetailActivity, "Could not save the copy.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubjectDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        subjectId = intent.getLongExtra(EXTRA_SUBJECT_ID, -1)

        val adapter = FileAdapter(
            subjectNames = { emptyMap() },
            onClick = { file -> FileActions.open(this, file) },
            onLongClick = { file -> showFileActions(file) }
        )
        binding.rvSubjectFiles.layoutManager = LinearLayoutManager(this)
        binding.rvSubjectFiles.adapter = adapter

        binding.btnBackSubject.setOnClickListener { finish() }
        binding.btnUploadFile.setOnClickListener {
            startActivity(UploadActivity.intent(this, subjectId))
        }

        lifecycleScope.launch {
            val subject = app.repository.subjectById(subjectId)
            binding.tvSubjectTitle.text = subject?.name ?: "Subject"
            app.repository.observeFilesForSubject(subjectId).collect { files ->
                binding.tvSubjectMeta.text =
                    "${files.size} file${if (files.size == 1) "" else "s"} in this folder"
                adapter.submit(files)
                binding.tvSubjectEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
                    "Download a copy" -> {
                        if (file.localPath.isEmpty()) {
                            Toast.makeText(this, "Sample entry — nothing to download.", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingDownload = file
                            createDocument.launch(file.fileName)
                        }
                    }
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
        fun intent(context: Context, subjectId: Long): Intent =
            Intent(context, SubjectDetailActivity::class.java)
                .putExtra(EXTRA_SUBJECT_ID, subjectId)
    }
}
