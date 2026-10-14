package com.parth.index.ui.subjects

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.data.model.Roles
import com.parth.index.data.model.SubjectEntity
import com.parth.index.databinding.FragmentSubjectsBinding
import com.parth.index.ui.adapters.SubjectRowAdapter
import com.parth.index.ui.subject.SubjectDetailActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The Folders tab: every subject as a row. Manage mode is role-shaped —
 *  Owner: add + rename + delete. Admin: add + rename, NO delete anywhere.
 *  Member: the Manage button itself never appears. Deleting a subject is a
 *  soft delete into Trash (see Profile → Trash), never an instant wipe. */
class SubjectsFragment : Fragment() {

    private var _binding: FragmentSubjectsBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as IndexApp

    private lateinit var adapter: SubjectRowAdapter
    private var manageMode = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubjectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val role = app.prefs.role
        val canManage = Roles.canManage(role)
        val isOwner = role == Roles.OWNER

        adapter = SubjectRowAdapter(
            onClick = { subject ->
                startActivity(SubjectDetailActivity.intent(requireContext(), subject.id))
            },
            onRename = { subject -> showRenameDialog(subject) },
            onDelete = { subject -> confirmDeleteSubject(subject) }
        )
        binding.rvSubjectsList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubjectsList.adapter = adapter

        binding.btnManageSubjects.visibility = if (canManage) View.VISIBLE else View.GONE
        binding.btnManageSubjects.setOnClickListener {
            manageMode = !manageMode
            binding.btnManageSubjects.text = if (manageMode) "Done" else "Manage subjects"
            binding.btnAddSubject.visibility =
                if (manageMode && canManage) View.VISIBLE else View.GONE
            adapter.setManageMode(manageMode, deleteAllowed = isOwner)
        }
        binding.btnAddSubject.setOnClickListener { showAddSubjectDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    app.repository.observeSubjects(),
                    app.repository.observeFiles()
                ) { subjects, files -> subjects to files }.collect { (subjects, files) ->
                    val counts = files.groupingBy { it.subjectId }.eachCount()
                    adapter.submit(subjects, counts)
                    binding.tvSubjectsEmpty.visibility =
                        if (subjects.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showAddSubjectDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etName = EditText(ctx).apply { hint = "Subject name" }
        container.addView(etName)
        val colors = listOf(
            "#2563EB", "#7C3AED", "#E17055", "#0D9488",
            "#DB2777", "#D97706", "#059669", "#636E72"
        )
        var selected = colors[0]
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 28, 0, 8)
        }
        val chips = mutableListOf<TextView>()
        for (hex in colors) {
            val chip = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 14 }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                    if (hex == selected) setStroke(6, Color.DKGRAY)
                }
                setOnClickListener {
                    selected = hex
                    chips.forEachIndexed { i, c ->
                        (c.background as GradientDrawable)
                            .setStroke(if (colors[i] == selected) 6 else 0, Color.DKGRAY)
                    }
                }
            }
            chips.add(chip)
            chipRow.addView(chip)
        }
        container.addView(chipRow)

        AlertDialog.Builder(ctx)
            .setTitle("Add subject")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "Give the subject a name.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch { app.repository.addSubject(name, selected) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(subject: SubjectEntity) {
        val ctx = requireContext()
        val etName = EditText(ctx).apply {
            setText(subject.name)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename subject")
            .setView(etName)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch { app.repository.renameSubject(subject.id, name) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSubject(subject: SubjectEntity) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val count = app.repository.fileCountForSubject(subject.id)
            AlertDialog.Builder(ctx)
                .setTitle("Delete “${subject.name}”?")
                .setMessage(
                    "This subject has $count file${if (count == 1) "" else "s"}. " +
                        "It moves to Trash for 30 days — nothing is wiped instantly, " +
                        "and the Owner can restore it from Profile → Trash."
                )
                .setPositiveButton("Move to Trash") { _, _ ->
                    lifecycleScope.launch { app.repository.deleteSubject(subject.id) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
