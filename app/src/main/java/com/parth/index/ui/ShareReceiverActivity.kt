package com.parth.index.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.parth.index.ui.upload.UploadActivity

/** The share-sheet target. When someone taps Share on a PDF or photo in
 *  WhatsApp, Files or Gallery and picks INDEX, Android starts THIS activity
 *  (see the SEND / SEND_MULTIPLE intent filters in the manifest). It does
 *  nothing but hand the shared file(s) to the upload screen, where the file
 *  is already attached and its name is pre-filled as the title — no
 *  save-to-phone-first detour. */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, UploadActivity::class.java).apply {
            action = intent.action
            type = intent.type
            putExtras(intent)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(target)
        finish()
    }
}
