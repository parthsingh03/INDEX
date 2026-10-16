package com.parth.index.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.R
import com.parth.index.databinding.ActivityMainBinding
import com.parth.index.firebase.ClassGate
import com.parth.index.ui.auth.JoinPendingActivity
import com.parth.index.ui.auth.SignInActivity
import com.parth.index.ui.chat.ChatFragment
import com.parth.index.ui.home.HomeFragment
import com.parth.index.ui.pdf.PdfMakerFragment
import com.parth.index.ui.requests.RequestsFragment
import com.parth.index.ui.subjects.SubjectsFragment
import com.parth.index.ui.upload.UploadActivity
import kotlinx.coroutines.launch

/** The shell: five tabs (Home, Folders, Make PDF, Chat, Requests) over one
 *  fragment container, with the Clean Campus bottom bar. Also the first
 *  stop for share-sheet intents — and the member gate: only signed-in,
 *  APPROVED class members ever see the tabs. Anyone else is handed to
 *  SignInActivity (signed out) or JoinPendingActivity (waiting/rejected)
 *  before a single fragment is built. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val fragments = mutableMapOf<Int, Fragment>()
    private var currentTab = TAB_HOME
    private var uiBuilt = false

    /** The v1.5 loading screen: on a cold start the integrated Cursor
     *  reveal plays (RevealView) while the member gate checks run
     *  underneath it; routing — to the tabs, to SignIn, to JoinPending —
     *  happens when BOTH the reveal has finished and the gate has an
     *  answer, whichever is slower. Recreations (dark-mode toggle,
     *  rotation) and share-sheet landings skip it entirely. */
    private var revealView: RevealView? = null
    private var revealFinished = false
    private var pendingRoute: (() -> Unit)? = null

    private fun routeOrDefer(action: () -> Unit) {
        if (revealView == null || revealFinished) {
            action()
        } else {
            pendingRoute = action
        }
    }

    /** Group-chat messages newer than my read marker, not sent by me —
     *  drives the Chat tab's badge (v1.4). Recomputed on every chat /
     *  member snapshot; [styleNav] decides whether it shows. */
    private var unreadChatCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isShareLanding = intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE
        if (savedInstanceState == null && !isShareLanding) {
            val reveal = RevealView(this)
            revealView = reveal
            reveal.onFinished = {
                revealFinished = true
                pendingRoute?.invoke()
                pendingRoute = null
            }
            setContentView(reveal)
            reveal.start()
        }

        if (ClassGate.currentUser() == null) {
            routeOrDefer {
                startActivity(Intent(this, SignInActivity::class.java))
                finish()
            }
            return
        }

        // Membership is a network read the first time (Firestore cache
        // answers after that) — build the UI only once approval is sure.
        lifecycleScope.launch {
            val member = runCatching { ClassGate.myMember() }.getOrNull()
            if (member == null || !member.isApproved) {
                routeOrDefer {
                    startActivity(Intent(this@MainActivity, JoinPendingActivity::class.java))
                    finish()
                }
                return@launch
            }
            syncIdentity(member)
            routeOrDefer {
                buildUi(savedInstanceState)
                watchMembership()
            }
        }
    }

    override fun onDestroy() {
        revealView?.halt()
        super.onDestroy()
    }

    /** The member record is the truth for who you are; Prefs is the cache
     *  every screen already reads. Keep them in step. */
    private fun syncIdentity(member: ClassGate.Member) {
        val app = application as IndexApp
        app.prefs.userName = member.name
        app.prefs.role = member.role
        app.prefs.setupDone = true
    }

    /** If the owner promotes/demotes or removes this user mid-session, the
     *  next member snapshot lands here: a role change refreshes Prefs; a
     *  lost approval leaves the app for the gate screen. */
    private fun watchMembership() {
        lifecycleScope.launch {
            ClassGate.observeMyMember().collect { member ->
                if (member == null || !member.isApproved) {
                    startActivity(
                        Intent(this@MainActivity, JoinPendingActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    finish()
                    return@collect
                }
                syncIdentity(member)
            }
        }
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        if (uiBuilt) return
        uiBuilt = true
        val app = application as IndexApp
        lifecycleScope.launch {
            ClassGate.classNameOrNull()?.let { app.prefs.className = it }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A file shared from WhatsApp / Files / Gallery can land here too —
        // hand it straight to the upload screen, which does the real work.
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            startActivity(
                Intent(this, UploadActivity::class.java).apply {
                    action = intent.action
                    type = intent.type
                    putExtras(intent)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }

        binding.navHome.setOnClickListener { selectTab(TAB_HOME) }
        binding.navFolders.setOnClickListener { selectTab(TAB_FOLDERS) }
        binding.navPdf.setOnClickListener { selectTab(TAB_PDF) }
        binding.navChat.setOnClickListener { selectTab(TAB_CHAT) }
        binding.navRequests.setOnClickListener { selectTab(TAB_REQUESTS) }

        watchUnreadChat()

        if (savedInstanceState != null) {
            // Recreation (the dark-mode toggle restarts this activity so
            // the new palette can apply, and so does rotation): the
            // FragmentManager restores the tab fragments during onCreate,
            // but this activity only sets its content view NOW — after
            // the async membership check — so the restored fragments end
            // up viewless: a black, empty container above a working tab
            // bar (Parth's bug report, 2026-10-15). Adopting them back
            // cannot repair their missing views, so drop them and let
            // selectTab add fresh fragments into the live container.
            val stale = (TAB_HOME..TAB_REQUESTS).mapNotNull {
                supportFragmentManager.findFragmentByTag("tab$it")
            }
            if (stale.isNotEmpty()) {
                val tx = supportFragmentManager.beginTransaction()
                stale.forEach { tx.remove(it) }
                tx.commitNowAllowingStateLoss()
            }
            fragments.clear()
        } else {
            // Fresh start: nothing to restore in practice, but adopt any
            // fragment the manager already holds instead of duplicating it.
            for (tab in TAB_HOME..TAB_REQUESTS) {
                supportFragmentManager.findFragmentByTag("tab$tab")?.let { fragments[tab] = it }
            }
        }
        selectTab(savedInstanceState?.getInt(STATE_TAB, TAB_HOME) ?: TAB_HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    fun openChat() = selectTab(TAB_CHAT)
    fun openRequests() = selectTab(TAB_REQUESTS)

    private fun fragmentFor(tab: Int): Fragment = fragments.getOrPut(tab) {
        when (tab) {
            TAB_HOME -> HomeFragment()
            TAB_FOLDERS -> SubjectsFragment()
            TAB_PDF -> PdfMakerFragment()
            TAB_CHAT -> ChatFragment()
            else -> RequestsFragment()
        }
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        val target = fragmentFor(tab)
        val tx = supportFragmentManager.beginTransaction()
        for ((key, fragment) in fragments) {
            if (key != tab) tx.hide(fragment)
        }
        if (target.isAdded) tx.show(target) else tx.add(R.id.fragmentContainer, target, "tab$tab")
        tx.commit()
        styleNav()
    }

    private fun styleNav() {
        val primary = ContextCompat.getColor(this, R.color.primary)
        val muted = ContextCompat.getColor(this, R.color.muted)
        fun style(label: TextView, icon: ImageView, selected: Boolean) {
            val color = if (selected) primary else muted
            label.setTextColor(color)
            icon.imageTintList = ColorStateList.valueOf(color)
        }
        style(binding.labelHome, binding.iconHome, currentTab == TAB_HOME)
        style(binding.labelFolders, binding.iconFolders, currentTab == TAB_FOLDERS)
        style(binding.labelChat, binding.iconChat, currentTab == TAB_CHAT)
        style(binding.labelRequests, binding.iconRequests, currentTab == TAB_REQUESTS)
        // The badge exists to pull you INTO chat — while you are in
        // chat it has nothing to say (ChatFragment is marking read).
        binding.badgeChat.visibility =
            if (unreadChatCount > 0 && currentTab != TAB_CHAT) View.VISIBLE else View.GONE
        if (unreadChatCount > 0) {
            binding.badgeChat.text = if (unreadChatCount > 99) "99+" else unreadChatCount.toString()
        }
    }

    /** Unread = chat messages newer than my member record's
     *  lastReadChatAt, sent by someone else, not tombstoned. Two live
     *  sources (chat + my member doc); whichever lands last recomputes. */
    private fun watchUnreadChat() {
        val app = application as IndexApp
        var lastReadAt = 0L
        var latestChat: List<com.parth.index.data.model.ChatMessageEntity> = emptyList()
        fun recompute() {
            val myUid = ClassGate.currentUid()
            unreadChatCount = latestChat.count { message ->
                message.timestamp > lastReadAt &&
                    !message.deletedForEveryone &&
                    message.senderUid.isNotEmpty() &&
                    message.senderUid != myUid
            }
            if (uiBuilt) styleNav()
        }
        lifecycleScope.launch {
            ClassGate.observeMyMember().collect { member ->
                lastReadAt = member?.lastReadChatAt ?: 0L
                recompute()
            }
        }
        lifecycleScope.launch {
            app.repository.observeChat().collect { messages ->
                latestChat = messages
                recompute()
            }
        }
    }

    companion object {
        private const val STATE_TAB = "state_tab"
        const val TAB_HOME = 0
        const val TAB_FOLDERS = 1
        const val TAB_PDF = 2
        const val TAB_CHAT = 3
        const val TAB_REQUESTS = 4
    }
}
