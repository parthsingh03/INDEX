package com.parth.index.ui.search

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.databinding.ActivitySearchBinding
import com.parth.index.ui.adapters.FileAdapter
import com.parth.index.util.FileActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One search across the whole class: titles, tags, the uploader's note,
 *  uploader names and subject names. Everything is matched locally over the
 *  repository's file list (in cloud mode the same filter runs over the
 *  synced cache / Firestore query — the screen does not change). */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val app get() = application as IndexApp
    private var searchJob: Job? = null
    private var subjectNames: Map<Long, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = FileAdapter(
            subjectNames = { subjectNames },
            onClick = { file -> FileActions.open(this, file) },
            onDownload = { file -> com.parth.index.util.FileSaver.save(this, file) }
        )
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = adapter

        lifecycleScope.launch {
            subjectNames = app.repository.observeSubjects().first()
                .associate { it.id to it.name }
        }

        binding.etSearchQuery.doAfterTextChanged { text ->
            val query = text?.toString()?.trim()?.lowercase().orEmpty()
            searchJob?.cancel()
            if (query.isEmpty()) {
                adapter.submit(emptyList())
                binding.tvSearchHint.visibility = View.VISIBLE
                return@doAfterTextChanged
            }
            binding.tvSearchHint.visibility = View.GONE
            searchJob = lifecycleScope.launch {
                val all = app.repository.observeFiles().first()
                adapter.submit(all.filter { it.matches(query) })
            }
        }
        binding.etSearchQuery.requestFocus()
    }

    private fun MaterialFileEntity.matches(query: String): Boolean {
        val subject = subjectNames[subjectId]?.lowercase().orEmpty()
        return title.lowercase().contains(query) ||
            note.lowercase().contains(query) ||
            tags.lowercase().contains(query) ||
            uploaderName.lowercase().contains(query) ||
            fileName.lowercase().contains(query) ||
            subject.contains(query)
    }
}
