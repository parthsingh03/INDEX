package com.parth.index.ui.requests

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.SubjectEntity
import com.parth.index.databinding.FragmentRequestsBinding
import com.parth.index.ui.adapters.RequestAdapter
import com.parth.index.ui.upload.UploadActivity
import com.parth.index.util.FileActions
import kotlinx.coroutines.launch

/** The Request board: "I need X" posts that close when someone uploads the
 *  material against them. This is the yesterday problem, reversed — instead
 *  of hunting WhatsApp, you ask here and the file comes to the request. */
class RequestsFragment : Fragment() {

    private var _binding: FragmentRequestsBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as IndexApp

    private lateinit var adapter: RequestAdapter
    private var subjects: List<SubjectEntity> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RequestAdapter(
            onFulfil = { request ->
                startActivity(
                    UploadActivity.intent(requireContext(), request.subjectId, request.id)
                )
            },
            onOpenFile = { request ->
                if (request.fulfilledFileId > 0) {
                    lifecycleScope.launch {
                        app.repository.fileById(request.fulfilledFileId)?.let { file ->
                            FileActions.open(requireActivity(), file)
                        }
                    }
                }
            }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequests.adapter = adapter
        binding.btnCreateRequest.setOnClickListener { showCreateDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.repository.observeRequests().collect { requests ->
                        adapter.submit(requests)
                        binding.tvRequestsEmpty.visibility =
                            if (requests.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    app.repository.observeSubjects().collect { subjects = it }
                }
            }
        }
    }

    private fun showCreateDialog() {
        val ctx = requireContext()
        if (subjects.isEmpty()) {
            Toast.makeText(ctx, "Create a subject first.", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etTitle = EditText(ctx).apply { hint = "What do you need? (e.g. Unit 2 handwritten notes)" }
        val spinner = Spinner(ctx)
        spinner.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, subjects.map { it.name }
        )
        container.addView(etTitle)
        container.addView(spinner)

        AlertDialog.Builder(ctx)
            .setTitle("Request material")
            .setView(container)
            .setPositiveButton("Post request") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(ctx, "Describe what you need.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val subject = subjects[spinner.selectedItemPosition]
                lifecycleScope.launch {
                    app.repository.addRequest(
                        MaterialRequestEntity(
                            title = title,
                            subjectId = subject.id,
                            subjectName = subject.name,
                            requesterName = app.prefs.userName
                        )
                    )
                    Toast.makeText(ctx, "Request posted.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
