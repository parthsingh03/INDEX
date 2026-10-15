package com.parth.index.ui.chat

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.parth.index.IndexApp
import com.parth.index.R
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.Roles
import com.parth.index.databinding.FragmentChatBinding
import com.parth.index.firebase.ClassGate
import com.parth.index.ui.adapters.ChatAdapter
import com.parth.index.ui.adapters.NoticeAdapter
import com.parth.index.util.FileActions
import com.parth.index.util.Format
import com.parth.index.util.Reactions
import kotlinx.coroutines.launch

/** Class Space: two segmented tabs.
 *  Group Chat — everyone talks; uploads auto-post here as FILE CARDS and
 *  tap through to the file. Delete-for-everyone tombstones a message for
 *  the whole class, WhatsApp-style; the sender can always do it to their
 *  own messages, and Owner/Admins can do it to anyone's.
 *  Notices — the quiet channel: only Owner/Admins can post; members read.
 *
 *  v1.4 Discord pack: a "new messages" divider at the point the member
 *  last read up to (member doc's lastReadChatAt, also behind the Chat
 *  tab's unread badge), emoji reactions (long-press, or tap a chip to
 *  toggle), quote-reply (long-press → Reply; the composer shows a quote
 *  strip), upload posts as cards, a slimmer density, and a member
 *  mini-profile card on tapping a sender's name. */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as IndexApp

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var noticeAdapter: NoticeAdapter
    private var showingNotices = false
    private var canPostNoticeCache = false

    /** Latest chat + file snapshots (the adapter reads files from here to
     *  dress upload cards). */
    private var currentMessages: List<ChatMessageEntity> = emptyList()
    private var filesById: Map<Long, MaterialFileEntity> = emptyMap()

    /** lastReadChatAt captured when this chat session started: the "new
     *  messages" divider is anchored to it and does not move while the
     *  member reads. -1 until the member record first arrives. */
    private var sessionLastReadAt: Long = -1L

    /** The newest timestamp this phone has already marked as read, so
     *  the marker only ever moves forward and writes stay rare. */
    private var markedReadUpTo: Long = 0L

    private var replyTarget: ChatMessageEntity? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = app.prefs
        val canPostNotice = Roles.canManage(prefs.role)

        chatAdapter = ChatAdapter(
            myUid = { ClassGate.currentUid() },
            fileLookup = { fileId -> filesById[fileId] },
            onClick = { message ->
                if (!message.deletedForEveryone && message.systemFileId > 0) {
                    lifecycleScope.launch {
                        app.repository.fileById(message.systemFileId)?.let { file ->
                            FileActions.open(requireActivity(), file)
                        }
                    }
                }
            },
            onLongClick = { message -> showMessageActions(message) },
            onToggleReaction = { message, emoji ->
                lifecycleScope.launch { app.repository.toggleReaction(message.id, emoji) }
            },
            onSenderClick = { message -> showMemberCard(message) },
            onQuoteClick = { message -> scrollToMessage(message.replyToId) }
        )
        binding.rvChat.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChat.adapter = chatAdapter

        noticeAdapter = NoticeAdapter()
        binding.rvNotices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotices.adapter = noticeAdapter

        binding.tabGroupChat.setOnClickListener { selectTab(notices = false) }
        binding.tabNotices.setOnClickListener { selectTab(notices = true) }
        selectTab(notices = false)

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val reply = replyTarget
            binding.etChatMessage.text.clear()
            clearReplyTarget()
            lifecycleScope.launch {
                app.repository.sendChat(text, prefs.userName, prefs.role, reply)
            }
        }
        binding.btnCancelReply.setOnClickListener { clearReplyTarget() }

        binding.btnPostNotice.setOnClickListener {
            val title = binding.etNoticeTitle.text.toString().trim()
            val body = binding.etNoticeBody.text.toString().trim()
            if (title.isEmpty() || body.isEmpty()) {
                Toast.makeText(requireContext(), "A notice needs a title and a message.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.etNoticeTitle.text.clear()
            binding.etNoticeBody.text.clear()
            lifecycleScope.launch {
                app.repository.addNotice(
                    NoticeEntity(
                        authorName = prefs.userName,
                        authorRole = prefs.role,
                        title = title,
                        body = body,
                        seenByCount = 1,
                        seenByMe = true
                    )
                )
                Toast.makeText(requireContext(), "Notice posted.", Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.repository.observeChat().collect { messages ->
                        currentMessages = messages
                        chatAdapter.submit(messages)
                        refreshDivider()
                        if (messages.isNotEmpty()) {
                            binding.rvChat.scrollToPosition(messages.size - 1)
                        }
                        markReadUpTo(messages.maxOfOrNull { it.timestamp } ?: 0L)
                    }
                }
                launch {
                    app.repository.observeFiles().collect { files ->
                        filesById = files.associateBy { it.id }
                        if (currentMessages.isNotEmpty()) chatAdapter.submit(currentMessages)
                    }
                }
                launch {
                    app.repository.observeNotices().collect { notices ->
                        noticeAdapter.submit(notices)
                    }
                }
                launch {
                    ClassGate.observeMyMember().collect { member ->
                        if (member != null && sessionLastReadAt < 0) {
                            sessionLastReadAt = member.lastReadChatAt
                            markedReadUpTo = maxOf(markedReadUpTo, member.lastReadChatAt)
                            refreshDivider()
                        }
                    }
                }
            }
        }

        // The notice composer exists only for roles that may post; members
        // see the read-only footer when they open the Notices tab.
        binding.layoutNoticeComposer.visibility = View.GONE
        binding.tvNoticeFooter.visibility = View.GONE
        this.canPostNoticeCache = canPostNotice
    }

    /** The divider goes above the first message that is newer than where
     *  this session started reading and was not sent by me. */
    private fun refreshDivider() {
        if (sessionLastReadAt < 0) return
        val myUid = ClassGate.currentUid()
        val first = currentMessages.firstOrNull { message ->
            message.timestamp > sessionLastReadAt &&
                !message.deletedForEveryone &&
                (myUid.isEmpty() || message.senderUid != myUid)
        }
        chatAdapter.setDividerBefore(first?.id ?: -1)
    }

    /** Move my read marker forward (member doc, cloud mode only). Runs
     *  while the chat is on screen — opening the chat IS reading it; the
     *  Chat tab's badge clears off the same marker. The visibility guard
     *  matters: the bottom bar HIDES fragments instead of pausing them,
     *  so without it the marker would advance while the member is off
     *  reading Home and the badge would never get to exist. (onPause
     *  also lands here, but a paused fragment is not visible either —
     *  the on-screen marking has already covered everything by then.) */
    private fun markReadUpTo(timestamp: Long) {
        if (!isVisible) return
        if (timestamp <= markedReadUpTo) return
        markedReadUpTo = timestamp
        if (ClassGate.currentUid().isEmpty()) return
        lifecycleScope.launch {
            runCatching { ClassGate.updateMyLastReadChatAt(timestamp) }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the chat: everything up to now counts as read, even if
        // the last snapshot raced us.
        markReadUpTo(System.currentTimeMillis())
    }

    private fun scrollToMessage(messageId: Long) {
        val index = currentMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            binding.rvChat.smoothScrollToPosition(index)
        } else {
            Toast.makeText(
                requireContext(),
                "The original message isn't loaded here.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setReplyTarget(message: ChatMessageEntity) {
        replyTarget = message
        binding.tvReplyStrip.text =
            "Replying to ${message.senderName}: ${message.text.take(80)}"
        binding.layoutReplyStrip.visibility = if (showingNotices) View.GONE else View.VISIBLE
    }

    private fun clearReplyTarget() {
        replyTarget = null
        binding.layoutReplyStrip.visibility = View.GONE
    }

    private fun selectTab(notices: Boolean) {
        showingNotices = notices
        val ctx = requireContext()
        val white = Color.WHITE
        val muted = ContextCompat.getColor(ctx, R.color.muted)
        if (notices) {
            binding.tabNotices.setBackgroundResource(R.drawable.bg_segment_selected)
            binding.tabNotices.setTextColor(white)
            binding.tabGroupChat.setBackgroundColor(Color.TRANSPARENT)
            binding.tabGroupChat.setTextColor(muted)
            binding.rvChat.visibility = View.GONE
            binding.rvNotices.visibility = View.VISIBLE
            binding.layoutChatComposer.visibility = View.GONE
            binding.layoutReplyStrip.visibility = View.GONE
            binding.layoutNoticeComposer.visibility =
                if (canPostNoticeCache) View.VISIBLE else View.GONE
            binding.tvNoticeFooter.visibility =
                if (canPostNoticeCache) View.GONE else View.VISIBLE
            lifecycleScope.launch { app.repository.markNoticesSeen() }
        } else {
            binding.tabGroupChat.setBackgroundResource(R.drawable.bg_segment_selected)
            binding.tabGroupChat.setTextColor(white)
            binding.tabNotices.setBackgroundColor(Color.TRANSPARENT)
            binding.tabNotices.setTextColor(muted)
            binding.rvChat.visibility = View.VISIBLE
            binding.rvNotices.visibility = View.GONE
            binding.layoutChatComposer.visibility = View.VISIBLE
            binding.layoutReplyStrip.visibility =
                if (replyTarget != null) View.VISIBLE else View.GONE
            binding.layoutNoticeComposer.visibility = View.GONE
            binding.tvNoticeFooter.visibility = View.GONE
        }
    }

    private fun showMessageActions(message: ChatMessageEntity) {
        if (message.deletedForEveryone) return
        val prefs = app.prefs
        val mine = message.senderName == prefs.userName
        val canModerate = Roles.canManage(prefs.role)
        val actions = mutableListOf<String>()
        // Reactions first (Discord-style long-press): tapping one toggles
        // it. Then Reply, then the delete actions as before.
        actions.addAll(Reactions.EMOJIS)
        if (message.systemFileId == 0L) actions.add("Reply")
        if (mine || canModerate) actions.add("Delete for everyone")
        actions.add("Delete for me")
        AlertDialog.Builder(requireContext())
            .setTitle("Message")
            .setItems(actions.toTypedArray()) { _, which ->
                when (val action = actions[which]) {
                    "Reply" -> setReplyTarget(message)
                    "Delete for everyone" -> lifecycleScope.launch {
                        // Tombstone: the words are replaced in the database,
                        // so every phone shows only the placeholder.
                        app.repository.updateChatMessage(
                            message.copy(text = "", deletedForEveryone = true)
                        )
                    }
                    "Delete for me" -> lifecycleScope.launch {
                        app.repository.updateChatMessage(message.copy(hiddenForMe = true))
                    }
                    else -> lifecycleScope.launch {
                        app.repository.toggleReaction(message.id, action)
                    }
                }
            }
            .show()
    }

    /** Mini profile card (Discord-style): who is this sender? Resolved
     *  from the class member record when we can (real role, email,
     *  member-since); the message's own snapshot is the fallback. */
    private fun showMemberCard(message: ChatMessageEntity) {
        lifecycleScope.launch {
            val member = if (message.senderUid.isNotEmpty()) {
                runCatching { ClassGate.memberFor(message.senderUid) }.getOrNull()
            } else {
                null
            }
            if (!isAdded) return@launch
            val ctx = requireContext()
            val name = member?.name ?: message.senderName
            val role = member?.role ?: message.senderRole
            val density = resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(22), dp(24), dp(26))
            }
            val avatar = TextView(ctx).apply {
                text = Format.initial(name)
                textSize = 24f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_circle_primary)
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            }
            container.addView(avatar)
            container.addView(TextView(ctx).apply {
                text = name
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
            val (roleLabel, roleColor) = when (role) {
                Roles.OWNER -> "OWNER" to 0xFFB46E0A.toInt()
                Roles.ADMIN -> "ADMIN" to ContextCompat.getColor(ctx, R.color.primary)
                else -> "MEMBER" to ContextCompat.getColor(ctx, R.color.muted)
            }
            container.addView(TextView(ctx).apply {
                text = roleLabel
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(roleColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            member?.email?.takeIf { it.isNotEmpty() }?.let { email ->
                container.addView(TextView(ctx).apply {
                    text = email
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(ctx, R.color.muted))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                })
            }
            member?.joinedAt?.takeIf { it > 0 }?.let { joinedAt ->
                container.addView(TextView(ctx).apply {
                    text = "Member since ${Format.date(joinedAt)}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.muted))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                })
            }
            BottomSheetDialog(ctx).apply {
                setContentView(container)
                show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
