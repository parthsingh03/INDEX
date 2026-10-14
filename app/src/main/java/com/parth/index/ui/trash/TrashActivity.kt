package com.parth.index.ui.trash

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.databinding.ActivityTrashBinding
import com.parth.index.ui.adapters.TrashAdapter
import com.parth.index.ui.adapters.TrashRow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** Trash: soft-deleted subjects and files, restorable. Nothing in INDEX is
 *  wiped the moment you tap delete — it lands here first. "Delete forever"
 *  is the only permanent action in the app, and it asks first. In cloud
 *  mode this screen is owner-focused; locally anyone previewing a role can
 *  look, which is fine for testing the flow. */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private val app get() = application as IndexApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val subjectAdapter = TrashAdapter(
            onRestore = { row -> lifecycleScope.launch { app.repository.restoreSubject(row.id) } },
            onDeleteForever = { row -> confirmForever(row) }
        )
        val fileAdapter = TrashAdapter(
            onRestore = { row -> lifecycleScope.launch { app.repository.restoreFile(row.id) } },
            onDeleteForever = { row -> confirmForever(row) }
        )
        binding.rvTrashSubjects.layoutManager = LinearLayoutManager(this)
        binding.rvTrashSubjects.adapter = subjectAdapter
        binding.rvTrashFiles.layoutManager = LinearLayoutManager(this)
        binding.rvTrashFiles.adapter = fileAdapter

        lifecycleScope.launch {
            combine(
                app.repository.observeTrashedSubjects(),
                app.repository.observeTrashedFiles()
            ) { subjects, files -> subjects to files }.collect { (subjects, files) ->
                subjectAdapter.submit(subjects.map {
                    TrashRow(it.id, isSubject = true, title = it.name, deletedAt = it.deletedAt)
                })
                fileAdapter.submit(files.map {
                    TrashRow(it.id, isSubject = false, title = it.title, deletedAt = it.deletedAt)
                })
                binding.tvTrashEmpty.visibility =
                    if (subjects.isEmpty() && files.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun confirmForever(row: TrashRow) {
        AlertDialog.Builder(this)
            .setTitle("Delete forever?")
            .setMessage("“${row.title}” will be permanently removed. This one cannot be undone.")
            .setPositiveButton("Delete forever") { _, _ ->
                lifecycleScope.launch {
                    if (row.isSubject) {
                        app.repository.deleteSubjectPermanently(row.id)
                    } else {
                        val file = app.repository.fileById(row.id)
                        app.repository.deleteFilePermanently(row.id)
                        file?.localPath?.takeIf { it.isNotEmpty() }?.let { path ->
                            runCatching { File(path).delete() }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
