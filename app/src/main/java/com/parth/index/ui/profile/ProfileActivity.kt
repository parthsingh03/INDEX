package com.parth.index.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.parth.index.BuildConfig
import com.parth.index.IndexApp
import com.parth.index.data.model.Roles
import com.parth.index.databinding.ActivityProfileBinding
import com.parth.index.firebase.ClassGate
import com.parth.index.ui.auth.SignInActivity
import com.parth.index.ui.members.MembersActivity
import com.parth.index.ui.trash.TrashActivity
import com.parth.index.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Profile & settings: who you are (from the class member record — there
 *  is no role switcher anymore, roles are real now), class members,
 *  storage usage, Trash, cache clearing, sign-out, and updates.
 *
 *  Updates: INDEX releases live on GitHub Releases. The check reads a tiny
 *  version file next to the releases and compares version codes. When a
 *  newer build exists, the Update button downloads the release APK and
 *  hands it to the system installer — the whole class updates in place,
 *  data untouched, because every release is signed with the same key. */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private val app get() = application as IndexApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = app.prefs
        binding.tvProfileName.text = prefs.userName
        binding.tvProfileEmail.text = ClassGate.currentUser()?.email.orEmpty()
        binding.tvProfileClass.text = "${prefs.className} · ${roleLabel(prefs.role)}"
        binding.tvAppVersion.text = "INDEX ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        binding.btnOpenMembers.visibility =
            if (Roles.canManage(prefs.role)) View.VISIBLE else View.GONE
        binding.btnOpenMembers.setOnClickListener {
            startActivity(Intent(this, MembersActivity::class.java))
        }
        binding.btnSignOut.setOnClickListener {
            SignInActivity.signOutEverywhere(this)
            startActivity(
                Intent(this, SignInActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
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
        binding.btnUpdate.setOnClickListener { downloadAndInstallUpdate() }

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
        binding.btnUpdate.visibility = View.GONE
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
            when {
                latest == null ->
                    binding.tvUpdateStatus.text =
                        "Couldn't check for updates — check the connection and try again."
                latest > BuildConfig.VERSION_CODE -> {
                    binding.tvUpdateStatus.text =
                        "A new version of INDEX is available. Tap Update — it installs " +
                            "over this one and all your data stays."
                    binding.btnUpdate.visibility = View.VISIBLE
                }
                else -> binding.tvUpdateStatus.text = "You're up to date."
            }
        }
    }

    /** Download the latest release APK into the cache dir (background),
     *  then fire the system installer through the FileProvider. If the
     *  download fails for any reason, fall back to the releases page in
     *  the browser so there is always a way to update. Android asks the
     *  user to allow installs from INDEX once — that is the standard
     *  prompt the REQUEST_INSTALL_PACKAGES permission exists for. */
    private fun downloadAndInstallUpdate() {
        binding.btnUpdate.isEnabled = false
        binding.progressUpdate.visibility = View.VISIBLE
        binding.tvUpdateStatus.text = "Downloading the update…"
        lifecycleScope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    val dest = File(cacheDir, "index-update.apk")
                    if (dest.exists()) dest.delete()
                    val conn = URL(RELEASE_APK_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 120_000
                    conn.instanceFollowRedirects = true
                    try {
                        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                        conn.inputStream.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (dest.length() == 0L) error("Empty download")
                    dest
                }
            }.getOrNull()

            binding.progressUpdate.visibility = View.GONE
            binding.btnUpdate.isEnabled = true
            if (apk == null) {
                binding.tvUpdateStatus.text =
                    "The download didn't work — opening the releases page instead."
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE_URL)))
                }
                return@launch
            }
            binding.tvUpdateStatus.text = "Opening the installer…"
            runCatching {
                val uri = FileProvider.getUriForFile(
                    this@ProfileActivity,
                    packageName + ".fileprovider",
                    apk
                )
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                binding.tvUpdateStatus.text =
                    "Could not open the installer — opening the releases page instead."
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE_URL)))
                }
            }
        }
    }

    companion object {
        /** Points at a one-line text file (the latest versionCode) hosted
         *  next to the GitHub Releases for parthsingh03/INDEX. */
        const val LATEST_VERSION_URL =
            "https://raw.githubusercontent.com/parthsingh03/INDEX/main/latest-version.txt"

        /** The in-app updater's download: GitHub Releases serves the asset
         *  named exactly this from the latest release. The CI workflow
         *  must publish the signed release APK under this name — see
         *  WIRING_NOTES.md. */
        const val RELEASE_APK_URL =
            "https://github.com/parthsingh03/INDEX/releases/latest/download/app-release.apk"

        const val RELEASES_PAGE_URL =
            "https://github.com/parthsingh03/INDEX/releases/latest"
    }
}
