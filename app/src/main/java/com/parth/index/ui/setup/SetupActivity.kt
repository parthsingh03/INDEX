package com.parth.index.ui.setup

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.parth.index.IndexApp
import com.parth.index.databinding.ActivitySetupBinding
import com.parth.index.ui.MainActivity
import kotlinx.coroutines.launch

/** First launch, seen once by the Owner: name the class, tick the subjects
 *  the class actually has (sensible first-semester defaults are prefilled
 *  and pre-ticked — drop the ones you do not have, add your own), and the
 *  class exists. Everything here can be changed later in Manage Subjects. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val app get() = application as IndexApp

    private val subjectChecks = mutableListOf<Pair<CheckBox, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etYourName.setText(app.prefs.userName)
        binding.etClassName.setText(app.prefs.className)

        for ((name, _) in DEFAULT_SUBJECTS) addSubjectCheck(name, checked = true)

        binding.btnAddCustomSubject.setOnClickListener {
            val name = binding.etCustomSubject.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            addSubjectCheck(name, checked = true)
            binding.etCustomSubject.text.clear()
        }

        binding.btnFinishSetup.setOnClickListener {
            val chosen = subjectChecks
                .filter { it.first.isChecked }
                .map { (_, name) -> name to colorFor(name) }
            if (chosen.isEmpty()) {
                Toast.makeText(this, "Keep at least one subject.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val yourName = binding.etYourName.text.toString().trim()
                .ifEmpty { app.prefs.userName }
            val className = binding.etClassName.text.toString().trim()
                .ifEmpty { app.prefs.className }
            app.prefs.userName = yourName
            app.prefs.className = className
            lifecycleScope.launch {
                app.repository.completeSetup(className, chosen)
                app.prefs.setupDone = true
                startActivity(
                    Intent(this@SetupActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
            }
        }
    }

    private fun addSubjectCheck(name: String, checked: Boolean) {
        val checkBox = CheckBox(this).apply {
            text = name
            isChecked = checked
            textSize = 15f
            setPadding(8, 14, 8, 14)
        }
        binding.llSetupSubjects.addView(checkBox)
        subjectChecks.add(checkBox to name)
    }

    private fun colorFor(name: String): String =
        DEFAULT_SUBJECTS.firstOrNull { it.first == name }?.second
            ?: EXTRA_COLORS[name.hashCode().mod(EXTRA_COLORS.size)]

    companion object {
        val DEFAULT_SUBJECTS = listOf(
            "Mathematics" to "#2563EB",
            "Programming in C" to "#7C3AED",
            "Mechanical Engg." to "#E17055",
            "Physics" to "#7C3AED",
            "Electronics" to "#0D9488",
            "Chemistry" to "#DB2777"
        )
        private val EXTRA_COLORS = listOf("#2563EB", "#0D9488", "#D97706", "#DB2777", "#059669")
    }
}
