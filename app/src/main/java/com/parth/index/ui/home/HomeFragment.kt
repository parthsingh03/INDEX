package com.parth.index.ui.home

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.Roles
import com.parth.index.databinding.FragmentHomeBinding
import com.parth.index.ui.adapters.DeadlineAdapter
import com.parth.index.ui.adapters.FileAdapter
import com.parth.index.ui.adapters.SubjectCardAdapter
import com.parth.index.ui.profile.ProfileActivity
import com.parth.index.ui.search.SearchActivity
import com.parth.index.ui.subject.SubjectDetailActivity
import com.parth.index.util.FileActions
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

/** Home: greeting, search entry, deadlines (with countdown), the subject
 *  grid and recent uploads — the Clean Campus design, plus the ONLY dark
 *  mode toggle in the app, in this header beside the avatar. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as IndexApp

    private lateinit var deadlineAdapter: DeadlineAdapter
    private lateinit var subjectAdapter: SubjectCardAdapter
    private lateinit var recentAdapter: FileAdapter
    private var subjectNames: Map<Long, String> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = app.prefs
        val canManage = Roles.canManage(prefs.role)

        binding.tvClassName.text = prefs.className
        binding.tvGreeting.text = greetingForNow(prefs.userName)
        binding.tvAvatar.text = prefs.userName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        binding.tvAvatar.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        // Dark mode lives HERE and nowhere else, exactly as designed: the
        // moon beside the avatar on Home. It flips the whole app palette.
        binding.btnDarkMode.setOnClickListener {
            prefs.darkMode = !prefs.darkMode
            AppCompatDelegate.setDefaultNightMode(
                if (prefs.darkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        binding.searchBar.setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        deadlineAdapter = DeadlineAdapter(
            canManage = canManage,
            onEdit = { showDeadlineDialog(it) },
            onDelete = { deadline ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete this deadline?")
                    .setMessage(deadline.title)
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch { app.repository.deleteDeadline(deadline.id) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        binding.rvDeadlines.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDeadlines.adapter = deadlineAdapter
        binding.btnCreateDeadline.visibility = if (canManage) View.VISIBLE else View.GONE
        binding.btnCreateDeadline.setOnClickListener { showDeadlineDialog(null) }

        subjectAdapter = SubjectCardAdapter { subject ->
            startActivity(SubjectDetailActivity.intent(requireContext(), subject.id))
        }
        binding.rvSubjects.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvSubjects.adapter = subjectAdapter
        binding.rvSubjects.isNestedScrollingEnabled = false

        recentAdapter = FileAdapter(
            subjectNames = { subjectNames },
            onClick = { file -> FileActions.open(requireActivity(), file) }
        )
        binding.rvRecent.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecent.adapter = recentAdapter
        binding.rvRecent.isNestedScrollingEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.repository.observeDeadlines().collect { deadlines ->
                        deadlineAdapter.submit(deadlines)
                        binding.tvNoDeadlines.visibility =
                            if (deadlines.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    combine(
                        app.repository.observeSubjects(),
                        app.repository.observeFiles()
                    ) { subjects, files -> subjects to files }.collect { (subjects, files) ->
                        subjectNames = subjects.associate { it.id to it.name }
                        val counts = files.groupingBy { it.subjectId }.eachCount()
                        subjectAdapter.submit(subjects, counts)
                        val recent = files.sortedByDescending { it.createdAt }.take(5)
                        recentAdapter.submit(recent)
                        binding.tvRecentEmpty.visibility =
                            if (recent.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun greetingForNow(userName: String): String {
        val first = userName.trim().split(" ").firstOrNull() ?: userName
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, $first"
    }

    /** Create/edit deadline. Date is picked with the system date picker and
     *  stored as a timestamp; Home works the countdown out from it. */
    private fun showDeadlineDialog(existing: DeadlineEntity?) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etTitle = EditText(ctx).apply {
            hint = "Title (e.g. Mechanical assignment)"
            setText(existing?.title ?: "")
        }
        val etSubject = EditText(ctx).apply {
            hint = "Subject (optional)"
            setText(existing?.subjectName ?: "")
        }
        val picked = Calendar.getInstance().apply {
            timeInMillis = existing?.dueDateMillis ?: (System.currentTimeMillis() + 2 * 86_400_000L)
        }
        val tvDate = TextView(ctx).apply {
            text = "Due: ${com.parth.index.util.Format.date(picked.timeInMillis)}"
            setPadding(0, 24, 0, 8)
            setOnClickListener {
                DatePickerDialog(
                    ctx,
                    { _, year, month, day ->
                        picked.set(year, month, day, 12, 0, 0)
                        text = "Due: ${com.parth.index.util.Format.date(picked.timeInMillis)}"
                    },
                    picked.get(Calendar.YEAR), picked.get(Calendar.MONTH),
                    picked.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        container.addView(etTitle)
        container.addView(etSubject)
        container.addView(tvDate)

        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Create deadline" else "Edit deadline")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(ctx, "Give the deadline a title.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (existing == null) {
                        app.repository.addDeadline(
                            DeadlineEntity(
                                title = title,
                                subjectName = etSubject.text.toString().trim(),
                                dueDateMillis = picked.timeInMillis,
                                createdBy = app.prefs.userName
                            )
                        )
                    } else {
                        app.repository.updateDeadline(
                            existing.copy(
                                title = title,
                                subjectName = etSubject.text.toString().trim(),
                                dueDateMillis = picked.timeInMillis
                            )
                        )
                    }
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
