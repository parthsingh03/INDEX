package com.parth.index.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.databinding.ItemTrashBinding
import com.parth.index.util.Format

/** One Trash row — works for both deleted files and deleted subjects.
 *  [id] and [isSubject] tell the screen what Restore / Delete-forever mean. */
data class TrashRow(
    val id: Long,
    val isSubject: Boolean,
    val title: String,
    val deletedAt: Long
)

class TrashAdapter(
    private val onRestore: (TrashRow) -> Unit,
    private val onDeleteForever: (TrashRow) -> Unit
) : RecyclerView.Adapter<TrashAdapter.Holder>() {

    private var items: List<TrashRow> = emptyList()

    fun submit(list: List<TrashRow>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
        with(holder.binding) {
            tvTrashTitle.text = row.title
            val kind = if (row.isSubject) "Subject" else "File"
            tvTrashMeta.text = "$kind · deleted ${Format.relative(row.deletedAt)}"
            btnRestoreTrash.setOnClickListener { onRestore(row) }
            btnDeleteForever.setOnClickListener { onDeleteForever(row) }
        }
    }

    class Holder(val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root)
}
