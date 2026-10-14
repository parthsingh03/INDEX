package com.parth.index.ui.chat

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.IndexApp
import com.parth.index.R
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.Roles
import com.parth.index.databinding.FragmentChatBinding
import com.parth.index.ui.adapters.ChatAdapter
import com.parth.index.ui.adapters.NoticeAdapter
import com.parth.index.util.FileActions
import kotlinx.coroutines.launch

/** Class Space: two segmented tabs.
 *  Group Chat — everyone talks; uploads auto-post here and tap through to
 *  the file. Delete-for-everyone tombstones a message for the whole class,
 *  WhatsApp-style; the sender can always do it to their own messages, and
 *  Owner/Admins can do it to anyone's.
 *  Notices — the quiet channel: only Owner/Admins can post; members read. */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as IndexApp

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var noticeAdapter: NoticeAdapter
    private var showingNotices = false

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
            onClick = { message ->
                if (!message.deletedForEveryone && message.systemFileId > 0) {
                    lifecycleScope.launch {
                        app.repository.fileById(message.systemFileId)?.let { file ->
                            FileActions.open(requireActivity(), file)
                        }
                    }
                }
            },
            onLongClick = { message -> showMessageActions(message) }
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
            binding.etChatMessage.text.clear()
            lifecycleScope.launch {
                app.repository.sendChat(text, prefs.userName, prefs.role)
            }
        }

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
                        chatAdapter.submit(messages)
                        if (messages.isNotEmpty()) {
                            binding.rvChat.scrollToPosition(messages.size - 1)
                        }
                    }
                }
                launch {
                    app.repository.observeNotices().collect { notices ->
                        noticeAdapter.submit(notices)
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

    private var canPostNoticeCache = false

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
        if (mine || canModerate) actions.add("Delete for everyone")
        actions.add("Delete for me")
        AlertDialog.Builder(requireContext())
            .setTitle("Message")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
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
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
