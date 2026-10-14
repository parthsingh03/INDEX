package com.parth.index.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.parth.index.IndexApp
import com.parth.index.R
import com.parth.index.databinding.ActivityMainBinding
import com.parth.index.ui.chat.ChatFragment
import com.parth.index.ui.home.HomeFragment
import com.parth.index.ui.pdf.PdfMakerFragment
import com.parth.index.ui.requests.RequestsFragment
import com.parth.index.ui.setup.SetupActivity
import com.parth.index.ui.subjects.SubjectsFragment
import com.parth.index.ui.upload.UploadActivity

/** The shell: five tabs (Home, Folders, Make PDF, Chat, Requests) over one
 *  fragment container, with the Clean Campus bottom bar. Also the first
 *  stop for share-sheet intents and the gate for first-run setup. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val fragments = mutableMapOf<Int, Fragment>()
    private var currentTab = TAB_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IndexApp

        // First run ever: the owner sets the class up before anything else.
        if (!app.prefs.setupDone) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
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

        // After a recreation (e.g. the dark-mode toggle restarts this
        // activity so the new palette can apply), the FragmentManager has
        // already restored the tab fragments — adopt them back into the map
        // instead of adding duplicates, and restore the open tab.
        for (tab in TAB_HOME..TAB_REQUESTS) {
            supportFragmentManager.findFragmentByTag("tab$tab")?.let { fragments[tab] = it }
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
        fun style(label: TextView, dot: View, selected: Boolean) {
            label.setTextColor(if (selected) primary else muted)
            dot.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
        style(binding.labelHome, binding.dotHome, currentTab == TAB_HOME)
        style(binding.labelFolders, binding.dotFolders, currentTab == TAB_FOLDERS)
        style(binding.labelChat, binding.dotChat, currentTab == TAB_CHAT)
        style(binding.labelRequests, binding.dotRequests, currentTab == TAB_REQUESTS)
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
