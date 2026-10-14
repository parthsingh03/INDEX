package com.parth.index.ui.adapters

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.databinding.ItemChatMessageBinding
import com.parth.index.databinding.ItemNoticeBinding
import com.parth.index.databinding.ItemRequestBinding
import com.parth.index.util.Format

/** Group chat rows. A deleted-for-everyone message renders as the WhatsApp
 *  tombstone — italic grey "This message was deleted" — because the original
 *  text was replaced in the database at delete time. Auto-posted upload
 *  messages (systemFileId > 0) tap through to the file. */
class ChatAdapter(
    private val onClick: (ChatMessageEntity) -> Unit,
    private val onLongClick: (ChatMessageEntity) -> Unit
) : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private var items: List<ChatMessageEntity> = emptyList()

    fun submit(list: List<ChatMessageEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        with(holder.binding) {
            tvChatTime.text = Format.time(message.timestamp)
            if (message.deletedForEveryone) {
                tvChatSender.text = message.senderName
                tvChatText.text = "This message was deleted"
                tvChatText.setTypeface(null, Typeface.ITALIC)
            } else {
                tvChatText.setTypeface(null, Typeface.NORMAL)
                tvChatSender.text =
                    if (message.systemFileId > 0) "${message.senderName} · INDEX"
                    else "${message.senderName} · ${message.senderRole}"
                tvChatText.text = message.text
            }
            root.setOnClickListener { onClick(message) }
            root.setOnLongClickListener { onLongClick(message); true }
        }
    }

    class Holder(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Notice cards, styled after the Clean Campus notices mockup: author with
 *  role in the name line, title, body, seen-by count. */
class NoticeAdapter : RecyclerView.Adapter<NoticeAdapter.Holder>() {

    private var items: List<NoticeEntity> = emptyList()

    fun submit(list: List<NoticeEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemNoticeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val notice = items[position]
        with(holder.binding) {
            tvNoticeAuthor.text = "${notice.authorName} · ${notice.authorRole}"
            tvNoticeTime.text = Format.dateTime(notice.timestamp)
            tvNoticeTitle.text = notice.title
            tvNoticeBody.text = notice.body
            tvNoticeSeen.text = "Seen by ${notice.seenByCount} classmates"
        }
    }

    class Holder(val binding: ItemNoticeBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Request board rows. Fulfil button only shows while the request is open. */
class RequestAdapter(
    private val onFulfil: (MaterialRequestEntity) -> Unit,
    private val onOpenFile: (MaterialRequestEntity) -> Unit
) : RecyclerView.Adapter<RequestAdapter.Holder>() {

    private var items: List<MaterialRequestEntity> = emptyList()

    fun submit(list: List<MaterialRequestEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRequestBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val request = items[position]
        with(holder.binding) {
            tvRequestTitle.text = request.title
            val subjectPart = if (request.subjectName.isNotEmpty()) " · ${request.subjectName}" else ""
            tvRequestMeta.text =
                "Asked by ${request.requesterName}$subjectPart · ${Format.relative(request.createdAt)}"
            if (request.status == MaterialRequestEntity.STATUS_OPEN) {
                tvRequestStatus.text = "OPEN"
                tvRequestStatus.setTextColor(0xFF2563EB.toInt())
                btnFulfilRequest.visibility = View.VISIBLE
            } else {
                tvRequestStatus.text = "FULFILLED by ${request.fulfilledBy}"
                tvRequestStatus.setTextColor(0xFF0D9488.toInt())
                btnFulfilRequest.visibility = View.GONE
            }
            btnFulfilRequest.setOnClickListener { onFulfil(request) }
            root.setOnClickListener {
                if (request.status != MaterialRequestEntity.STATUS_OPEN) onOpenFile(request)
            }
        }
    }

    class Holder(val binding: ItemRequestBinding) : RecyclerView.ViewHolder(binding.root)
}
