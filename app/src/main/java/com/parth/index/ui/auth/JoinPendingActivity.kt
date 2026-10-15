package com.parth.index.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.databinding.ActivityJoinPendingBinding
import com.parth.index.firebase.ClassGate
import com.parth.index.ui.MainActivity
import com.parth.index.ui.setup.SetupActivity
import kotlinx.coroutines.launch

/** The gate between "signed in" and "in the class". Exactly three ways
 *  through, decided by the signed-in person's member document:
 *
 *   1. The OWNER (Google account matches CloudConfig.OWNER_EMAIL): on
 *      first ever sign-in the class is bootstrapped around them — owner
 *      member record + class document + the default subjects — and they
 *      walk straight in. No approval step for the owner, ever.
 *   2. An APPROVED member: straight in.
 *   3. Everyone else: a PENDING member record is their join request. This
 *      screen waits on it live — the moment an owner/admin approves, the
 *      app opens by itself. If the record is deleted (rejected), the
 *      declined state shows here with a chance to ask again.
 *
 *  Entering the app syncs the member's real name + role into Prefs: every
 *  existing screen reads identity from Prefs, so they all show the right
 *  name and the right buttons with no changes of their own. */
class JoinPendingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJoinPendingBinding
    private val app get() = application as IndexApp

    private var entered = false
    private var actedOnMissingDoc = false
    private var sawMemberDoc = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinPendingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = ClassGate.currentUser()
        if (user == null) {
            goToSignIn()
            return
        }

        binding.btnGateSignOut.setOnClickListener {
            SignInActivity.signOutEverywhere(this)
            goToSignIn()
        }
        binding.btnGatePrimary.setOnClickListener {
            // "Ask to join again" after a rejection, or "Try again" after
            // an error — both simply re-run the missing-document path.
            actedOnMissingDoc = false
            sawMemberDoc = false
            showWaiting("Checking your place in the class.")
            lifecycleScope.launch { handleMissingDocument() }
        }

        lifecycleScope.launch {
            ClassGate.observeMyMember().collect { member ->
                when {
                    member == null -> {
                        if (sawMemberDoc) showDeclined()
                        else if (!actedOnMissingDoc) {
                            actedOnMissingDoc = true
                            handleMissingDocument()
                        }
                    }
                    member.isApproved -> {
                        sawMemberDoc = true
                        enterApp(member)
                    }
                    else -> {
                        sawMemberDoc = true
                        showPending(user.email.orEmpty())
                    }
                }
            }
        }
    }

    /** No member document for this signed-in user: either the owner
     *  bootstrapping the class, or a new face creating their join request. */
    private suspend fun handleMissingDocument() {
        val user = ClassGate.currentUser() ?: return
        runCatching {
            if (ClassGate.isOwnerEmail(user.email)) {
                ClassGate.writeOwnerMember(user)
                // Creates the class document + seeds the default subjects
                // (only if the class does not exist yet — the repository
                // method is a no-op for an already-set-up class).
                app.repository.completeSetup(
                    app.prefs.className,
                    SetupActivity.DEFAULT_SUBJECTS
                )
            } else {
                ClassGate.requestToJoin(user)
            }
        }.onFailure {
            showError(it)
        }
        // The member listener picks the new document up and moves on.
    }

    private fun enterApp(member: ClassGate.Member) {
        if (entered) return
        entered = true
        lifecycleScope.launch {
            app.prefs.userName = member.name
            app.prefs.role = member.role
            ClassGate.classNameOrNull()?.let { app.prefs.className = it }
            app.prefs.setupDone = true
            startActivity(
                Intent(this@JoinPendingActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    private fun showWaiting(message: String) {
        binding.tvGateTitle.text = "One moment…"
        binding.tvGateMessage.text = message
        binding.progressGate.visibility = View.VISIBLE
        binding.btnGatePrimary.visibility = View.GONE
    }

    private fun showPending(email: String) {
        binding.tvGateTitle.text = "You're on the list"
        binding.tvGateMessage.text =
            "Signed in as $email.\n\n" +
                "The class owner has to approve you before anything opens — " +
                "this screen lets you in by itself the moment they tap Approve. " +
                "No need to keep it open; approval works whenever it happens."
        binding.progressGate.visibility = View.VISIBLE
        binding.btnGatePrimary.visibility = View.GONE
    }

    private fun showDeclined() {
        binding.tvGateTitle.text = "Not approved"
        binding.tvGateMessage.text =
            "Your join request was declined by the class admins. " +
                "If that was a mistake, ask them in person — then tap below to ask again."
        binding.progressGate.visibility = View.GONE
        binding.btnGatePrimary.text = "Ask to join again"
        binding.btnGatePrimary.visibility = View.VISIBLE
    }

    private fun showError(error: Throwable) {
        binding.tvGateTitle.text = "Something went wrong"
        binding.tvGateMessage.text =
            "Could not reach the class database: ${error.message ?: "unknown error"}. " +
                "Check the internet connection and try again."
        binding.progressGate.visibility = View.GONE
        binding.btnGatePrimary.text = "Try again"
        binding.btnGatePrimary.visibility = View.VISIBLE
    }

    private fun goToSignIn() {
        startActivity(
            Intent(this, SignInActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }
}
