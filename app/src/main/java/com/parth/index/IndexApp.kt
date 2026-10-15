package com.parth.index

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.parth.index.data.Prefs
import com.parth.index.data.repository.IndexRepository
import com.parth.index.firebase.FirebaseRepository

/** App-wide singletons. Screens reach the data layer through
 *  (application as IndexApp).repository — always the interface, never the
 *  concrete class — and settings through prefs. */
class IndexApp : Application() {

    lateinit var prefs: Prefs
        private set

    lateinit var repository: IndexRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Cloud mode: the class lives in Firestore (+ Cloudinary for file
        // bytes) and is shared by every member's phone. LocalRepository /
        // Room remain in the tree, unused, as the offline reference
        // implementation of the same interface.
        repository = FirebaseRepository()
        // Apply the saved dark-mode choice before any screen draws.
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
