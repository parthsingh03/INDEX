package com.parth.index.ui.profile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.BuildConfig
import com.parth.index.IndexApp
import com.parth.index.data.model.Roles
import com.parth.index.databinding.ActivityProfileBinding
import com.parth.index.ui.trash.TrashActivity
import com.parth.index.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Profile & settings: who you are, the demo role preview switcher (local
 *  mode only — real roles come from Google sign-in later), storage usage,
 *  Trash, cache clearing, and the update check.
 *
 *  Update check: INDEX releases live on GitHub Releases. The check reads a
 *  tiny version file next to the releases and compares version codes. In
 *  local mode (no published release yet) it simply reports that the check
 *  is unavailable — it must never crash or nag. */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private val app get() = application as IndexApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = app.prefs
        binding.tvProfileName.text = prefs.userName
        binding.tvProfileClass.text = "${prefs.className} · ${roleLabel(prefs.role)}"
        binding.tvAppVersion.text = "INDEX ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        when (prefs.role) {
            Roles.ADMIN -> binding.rbAdmin.isChecked = true
            Roles.MEMBER -> binding.rbMember.isChecked = true
            else -> binding.rbOwner.isChecked = true
        }
        binding.rgRolePreview.setOnCheckedChangeListener { _, checkedId ->
            prefs.role = when (checkedId) {
                binding.rbAdmin.id -> Roles.ADMIN
                binding.rbMember.id -> Roles.MEMBER
                else -> Roles.OWNER
            }
            binding.tvProfileClass.text = "${prefs.className} · ${roleLabel(prefs.role)}"
            Toast.makeText(
                this,
                "Role preview: ${roleLabel(prefs.role)}. Go back and look at Home / Folders / Notices — the buttons change with the role.",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnOpenTrash.setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        binding.btnClearCache.setOnClickListener {
            lifecycleScope.launch {
                val freed = withContext(Dispatchers.IO) {
                    val before = cacheDir.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L }
                    cacheDir.deleteRecursively()
                    cacheDir.mkdirs()
                    before
                }
                Toast.makeText(
                    this@ProfileActivity,
                    "Cache cleared (${Format.size(freed)} freed). Your files are untouched.",
                    Toast.LENGTH_SHORT
                ).show()
                refreshStorage()
            }
        }
        binding.btnCheckUpdates.setOnClickListener { checkForUpdates() }

        refreshStorage()
    }

    override fun onResume() {
        super.onResume()
        refreshStorage()
    }

    private fun refreshStorage() {
        lifecycleScope.launch {
            val stored = app.repository.totalStoredBytes()
            val localFiles = withContext(Dispatchers.IO) {
                File(filesDir, "materials").walkBottomUp()
                    .sumOf { if (it.isFile) it.length() else 0L }
            }
            binding.tvStorageUsage.text =
                "Material stored for the class: ${Format.size(stored)} · " +
                    "copies on this phone: ${Format.size(localFiles)}"
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        Roles.OWNER -> "Owner"
        Roles.ADMIN -> "Admin"
        else -> "Member"
    }

    private fun checkForUpdates() {
        binding.tvUpdateStatus.text = "Checking…"
        lifecycleScope.launch {
            val latest = runCatching {
                withContext(Dispatchers.IO) {
                    val conn = URL(LATEST_VERSION_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    try {
                        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                        conn.inputStream.bufferedReader().readText().trim()
                            .lineSequence().firstOrNull()?.trim()?.toIntOrNull()
                            ?: error("Bad version file")
                    } finally {
                        conn.disconnect()
                    }
                }
            }.getOrNull()
            binding.tvUpdateStatus.text = when {
                latest == null ->
                    "Update check unavailable in local mode. When INDEX is published, " +
                        "new versions will appear here as an Update button — your data stays."
                latest > BuildConfig.VERSION_CODE ->
                    "A new version of INDEX is available. Get it from the INDEX releases page — " +
                        "it installs over this one and all your data stays."
                else -> "You're up to date."
            }
        }
    }

    companion object {
        /** Points at a one-line text file (the latest versionCode) hosted
         *  next to the GitHub Releases for parthsingh03/INDEX. Until that
         *  repo + file exist, the check above degrades gracefully. */
        const val LATEST_VERSION_URL =
            "https://raw.githubusercontent.com/parthsingh03/INDEX/main/latest-version.txt"
    }
}
