package com.parth.index.ui.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.data.model.SubjectEntity
import com.parth.index.databinding.ItemSubjectCardBinding
import com.parth.index.databinding.ItemSubjectRowBinding
import com.parth.index.util.Format

/** Coloured circle with the subject's initial, like the Clean Campus cards. */
fun subjectCircle(view: android.widget.TextView, subject: SubjectEntity) {
    val color = runCatching { Color.parseColor(subject.colorHex) }.getOrDefault(0xFF2563EB.toInt())
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
    view.background = drawable
    view.text = Format.initial(subject.name)
}

/** Home screen subject grid card. */
class SubjectCardAdapter(
    private val onClick: (SubjectEntity) -> Unit
) : RecyclerView.Adapter<SubjectCardAdapter.Holder>() {

    private var items: List<SubjectEntity> = emptyList()
    private var counts: Map<Long, Int> = emptyMap()

    fun submit(list: List<SubjectEntity>, fileCounts: Map<Long, Int>) {
        items = list
        counts = fileCounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSubjectCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val subject = items[position]
        with(holder.binding) {
            subjectCircle(tvSubjectCircle, subject)
            tvSubjectName.text = subject.name
            val count = counts[subject.id] ?: 0
            tvSubjectCount.text = "$count file${if (count == 1) "" else "s"}"
            root.setOnClickListener { onClick(subject) }
        }
    }

    class Holder(val binding: ItemSubjectCardBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Folders tab row. In manage mode it grows Rename/Delete buttons; who is
 *  allowed to see Delete is decided by the screen (owner only), not here. */
class SubjectRowAdapter(
    private val onClick: (SubjectEntity) -> Unit,
    private val onRename: (SubjectEntity) -> Unit,
    private val onDelete: (SubjectEntity) -> Unit
) : RecyclerView.Adapter<SubjectRowAdapter.Holder>() {

    private var items: List<SubjectEntity> = emptyList()
    private var counts: Map<Long, Int> = emptyMap()
    private var manageMode = false
    private var canDelete = false

    fun submit(list: List<SubjectEntity>, fileCounts: Map<Long, Int>) {
        items = list
        counts = fileCounts
        notifyDataSetChanged()
    }

    fun setManageMode(enabled: Boolean, deleteAllowed: Boolean) {
        manageMode = enabled
        canDelete = deleteAllowed
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSubjectRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val subject = items[position]
        with(holder.binding) {
            subjectCircle(tvRowCircle, subject)
            tvRowName.text = subject.name
            val count = counts[subject.id] ?: 0
            tvRowCount.text = "$count file${if (count == 1) "" else "s"}"
            btnRenameSubject.visibility = if (manageMode) View.VISIBLE else View.GONE
            btnDeleteSubject.visibility =
                if (manageMode && canDelete) View.VISIBLE else View.GONE
            root.setOnClickListener { if (!manageMode) onClick(subject) }
            btnRenameSubject.setOnClickListener { onRename(subject) }
            btnDeleteSubject.setOnClickListener { onDelete(subject) }
        }
    }

    class Holder(val binding: ItemSubjectRowBinding) : RecyclerView.ViewHolder(binding.root)
}
