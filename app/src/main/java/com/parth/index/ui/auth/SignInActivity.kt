package com.parth.index.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.parth.index.databinding.ActivitySigninBinding
import com.parth.index.firebase.ClassGate
import com.parth.index.firebase.awaitTask
import kotlinx.coroutines.launch

/** The front door. INDEX has exactly one way in — Google sign-in — so
 *  nobody invents a password and account recovery is Google's problem.
 *  After Firebase accepts the Google credential, JoinPendingActivity
 *  decides what this person is: the owner bootstrapping the class, an
 *  approved member walking in, or a new face waiting for approval.
 *
 *  The web client id comes from google-services.json (generated into
 *  resources by the google-services Gradle plugin). It is looked up by
 *  name instead of via R.string so a project json that lacks the web
 *  OAuth client degrades to a clear "not configured" message here
 *  instead of breaking the build. See WIRING_NOTES.md. */
class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySigninBinding
    private var googleClient: GoogleSignInClient? = null

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        lifecycleScope.launch {
            runCatching {
                val account = task.awaitTask()
                val idToken = account.idToken
                    ?: error("Google did not return an ID token.")
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(credential).awaitTask()
            }.onSuccess {
                goToGate()
            }.onFailure {
                showError("Sign-in failed: ${it.message ?: "please try again"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySigninBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Already signed in from a previous session: straight to the gate.
        if (ClassGate.currentUser() != null) {
            goToGate()
            return
        }

        val webClientId = webClientId()
        if (webClientId == null) {
            binding.btnGoogleSignIn.isEnabled = false
            showError(
                "Google sign-in is not configured for this build yet. " +
                    "The Firebase console steps in WIRING_NOTES.md come first."
            )
            return
        }
        googleClient = GoogleSignIn.getClient(
            this,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
        )
        binding.btnGoogleSignIn.setOnClickListener {
            binding.tvSignInError.visibility = View.GONE
            binding.progressSignIn.visibility = View.VISIBLE
            binding.btnGoogleSignIn.isEnabled = false
            signInLauncher.launch(googleClient?.signInIntent)
        }
    }

    private fun webClientId(): String? {
        val resId = resources.getIdentifier(
            "default_web_client_id", "string", packageName
        )
        if (resId == 0) return null
        return runCatching { getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun showError(message: String) {
        binding.progressSignIn.visibility = View.GONE
        binding.btnGoogleSignIn.isEnabled = true
        binding.tvSignInError.text = message
        binding.tvSignInError.visibility = View.VISIBLE
    }

    private fun goToGate() {
        startActivity(
            Intent(this, JoinPendingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    companion object {
        /** Full sign-out: Firebase AND the Google account picker, so the
         *  next sign-in starts from the account chooser. Used by the gate
         *  and Profile screens. */
        fun signOutEverywhere(context: Context) {
            ClassGate.signOut()
            runCatching {
                val client = GoogleSignIn.getClient(
                    context,
                    GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                )
                client.signOut()
            }
        }
    }
}
