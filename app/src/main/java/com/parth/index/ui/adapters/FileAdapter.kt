package com.parth.index.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.databinding.ItemFileBinding
import com.parth.index.util.FileActions
import com.parth.index.util.Format

/** One material file row — used by Home (recent), subject detail and search
 *  results so a file looks the same everywhere. The uploader's note shows
 *  under the title; there is intentionally no comment affordance. */
class FileAdapter(
    private val subjectNames: () -> Map<Long, String>,
    private val onClick: (MaterialFileEntity) -> Unit,
    private val onLongClick: (MaterialFileEntity) -> Unit = {}
) : RecyclerView.Adapter<FileAdapter.Holder>() {

    private var items: List<MaterialFileEntity> = emptyList()

    fun submit(list: List<MaterialFileEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = items[position]
        with(holder.binding) {
            FileActions.styleBadge(tvFileBadge, file)
            tvFileTitle.text = file.title
            tvFilePinned.visibility = if (file.pinned) View.VISIBLE else View.GONE
            val subject = subjectNames()[file.subjectId]
            val parts = listOfNotNull(
                subject,
                file.uploaderName,
                Format.relative(file.createdAt),
                if (file.sizeBytes > 0) Format.size(file.sizeBytes) else null
            )
            tvFileMeta.text = parts.joinToString(" · ")
            if (file.note.isNotBlank()) {
                tvFileNote.visibility = View.VISIBLE
                tvFileNote.text = "“${file.note}” — ${file.uploaderName}"
            } else {
                tvFileNote.visibility = View.GONE
            }
            root.setOnClickListener { onClick(file) }
            root.setOnLongClickListener { onLongClick(file); true }
        }
    }

    class Holder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)
}
