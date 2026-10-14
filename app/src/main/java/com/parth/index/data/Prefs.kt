package com.parth.index.data

import android.content.Context
import com.parth.index.data.model.Roles

/** Small on-phone settings: who "you" are in this demo, whether first-run
 *  setup is done, class name, and the dark-mode choice. When Google sign-in
 *  arrives with Firebase, the name/role stored here are replaced by the
 *  signed-in account and the class membership record — see FIREBASE_SETUP.md. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("index_prefs", Context.MODE_PRIVATE)

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    var className: String
        get() = sp.getString("class_name", "ECE · Semester 1") ?: "ECE · Semester 1"
        set(v) = sp.edit().putString("class_name", v).apply()

    var userName: String
        get() = sp.getString("user_name", "Parth Singh") ?: "Parth Singh"
        set(v) = sp.edit().putString("user_name", v).apply()

    /** Demo role preview. Local mode has no real accounts, so the Profile
     *  screen lets the owner flip this to preview exactly what an Admin or a
     *  Member sees (which buttons appear and which do not). */
    var role: String
        get() = sp.getString("role", Roles.OWNER) ?: Roles.OWNER
        set(v) = sp.edit().putString("role", v).apply()

    var darkMode: Boolean
        get() = sp.getBoolean("dark_mode", false)
        set(v) = sp.edit().putBoolean("dark_mode", v).apply()
}
