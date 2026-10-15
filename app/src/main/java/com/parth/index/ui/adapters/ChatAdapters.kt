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

/** Group chat rows (v1.4 Discord pack). A deleted-for-everyone message
 *  renders as the WhatsApp tombstone — italic grey "This message was
 *  deleted" — because the original text was replaced in the database at
 *  delete time. Upload auto-posts (systemFileId > 0) render as a tinted
 *  FILE CARD and tap through to the file; quote-replies carry their
 *  quoted strip; reactions render as tappable chips under the bubble;
 *  one chosen row carries the "new messages" divider above it. */
class ChatAdapter(
    private val myUid: () -> String,
    private val fileLookup: (Long) -> com.parth.index.data.model.MaterialFileEntity?,
    private val onClick: (ChatMessageEntity) -> Unit,
    private val onLongClick: (ChatMessageEntity) -> Unit,
    private val onToggleReaction: (ChatMessageEntity, String) -> Unit,
    private val onSenderClick: (ChatMessageEntity) -> Unit,
    private val onQuoteClick: (ChatMessageEntity) -> Unit
) : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private var items: List<ChatMessageEntity> = emptyList()

    /** Id of the message the "new messages" divider sits above (-1: none). */
    private var dividerBeforeId: Long = -1

    fun submit(list: List<ChatMessageEntity>) {
        items = list
        notifyDataSetChanged()
    }

    fun setDividerBefore(messageId: Long) {
        if (dividerBeforeId == messageId) return
        dividerBeforeId = messageId
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
            tvNewDivider.visibility =
                if (message.id == dividerBeforeId && dividerBeforeId > 0) View.VISIBLE
                else View.GONE
            tvChatTime.text = Format.time(message.timestamp)
            tvChatSender.setOnClickListener { onSenderClick(message) }

            if (message.deletedForEveryone) {
                tvChatSender.text = message.senderName
                tvChatText.visibility = View.VISIBLE
                tvChatText.text = "This message was deleted"
                tvChatText.setTypeface(null, Typeface.ITALIC)
                layoutQuote.visibility = View.GONE
                layoutFileCard.visibility = View.GONE
                layoutReactions.visibility = View.GONE
                root.setOnClickListener { onClick(message) }
                root.setOnLongClickListener { onLongClick(message); true }
                return@with
            }
            tvChatText.setTypeface(null, Typeface.NORMAL)

            val file = if (message.systemFileId > 0) fileLookup(message.systemFileId) else null
            if (message.systemFileId > 0) {
                // Upload auto-post: the CARD, never a text bubble.
                tvChatSender.text = "${message.senderName} · ${message.senderRole}"
                tvChatText.visibility = View.GONE
                layoutQuote.visibility = View.GONE
                layoutFileCard.visibility = View.VISIBLE
                if (file != null) {
                    com.parth.index.util.FileActions.styleBadge(tvCardBadge, file)
                    tvCardTitle.text = file.title
                    tvCardMeta.text =
                        "${com.parth.index.util.FileActions.badge(file).first} • " +
                            Format.size(file.sizeBytes)
                } else {
                    tvCardTitle.text = message.text
                    tvCardMeta.text = "File"
                }
            } else {
                tvChatSender.text = "${message.senderName} · ${message.senderRole}"
                tvChatText.visibility = View.VISIBLE
                tvChatText.text = message.text
                layoutFileCard.visibility = View.GONE
                if (message.replyToId > 0) {
                    layoutQuote.visibility = View.VISIBLE
                    tvQuoteSender.text = message.replyToSender
                    tvQuoteText.text = message.replyToText
                    layoutQuote.setOnClickListener { onQuoteClick(message) }
                } else {
                    layoutQuote.visibility = View.GONE
                }
            }

            bindReactions(holder, message)

            root.setOnClickListener { onClick(message) }
            root.setOnLongClickListener { onLongClick(message); true }
        }
    }

    /** One chip per emoji in use: "👍 3". My own reactions are
     *  highlighted; tapping any chip toggles mine — that is how you
     *  react again (or un-react) from the row itself. */
    private fun bindReactions(holder: Holder, message: ChatMessageEntity) {
        val reactions = com.parth.index.util.Reactions.parse(message.reactionsJson)
        with(holder.binding.layoutReactions) {
            removeAllViews()
            if (reactions.isEmpty()) {
                visibility = View.GONE
                return
            }
            visibility = View.VISIBLE
            val uid = myUid()
            val density = context.resources.displayMetrics.density
            for ((emoji, uids) in reactions) {
                val mine = uids.contains(uid)
                val chip = android.widget.TextView(context).apply {
                    text = "$emoji ${uids.size}"
                    textSize = 12f
                    setPadding(
                        (7 * density).toInt(), (3 * density).toInt(),
                        (7 * density).toInt(), (3 * density).toInt()
                    )
                    setBackgroundResource(
                        if (mine) com.parth.index.R.drawable.bg_reaction_chip_mine
                        else com.parth.index.R.drawable.bg_reaction_chip
                    )
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            context,
                            if (mine) com.parth.index.R.color.primary
                            else com.parth.index.R.color.on_surface
                        )
                    )
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (5 * density).toInt() }
                    setOnClickListener { onToggleReaction(message, emoji) }
                }
                addView(chip)
            }
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
