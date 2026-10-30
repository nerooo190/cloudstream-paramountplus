package de.cloudstream.paramountplus

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Native widgets also expose keyboard/focus actions on Android TV. */
internal class ParamountSettings(private val activity: Activity, private val api: ParamountApi) {
    private val scope = MainScope()

    fun show() {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding)
        }
        val status = TextView(activity).apply { text = statusText() }
        val email = EditText(activity).apply {
            hint = "Paramount+-E-Mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val password = EditText(activity).apply {
            hint = "Passwort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val progress = ProgressBar(activity).apply { visibility = View.GONE }
        val login = Button(activity).apply { text = "Anmelden" }
        val profiles = Button(activity).apply { text = "Profil wählen" }
        val logout = Button(activity).apply { text = "Abmelden" }
        listOf(status, email, password, progress, login, profiles, logout).forEach { content.addView(it) }
        val dialog = AlertDialog.Builder(activity).setTitle("Paramount+ International")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Schließen", null).create()
        val controls = listOf(email, password, login, profiles, logout)

        fun task(action: suspend () -> String) {
            scope.launch {
                controls.forEach { it.isEnabled = false }
                progress.visibility = View.VISIBLE
                try {
                    status.text = action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status.text = if (e is ParamountException) e.message else "Die Aktion ist fehlgeschlagen. Bitte erneut versuchen."
                } finally {
                    password.text.clear()
                    controls.forEach { it.isEnabled = true }
                    progress.visibility = View.GONE
                }
            }
        }

        login.setOnClickListener {
            if (email.text.isBlank() || password.text.isEmpty()) {
                status.text = "E-Mail und Passwort eingeben."
            } else {
                val address = email.text.toString()
                val secret = password.text.toString()
                password.text.clear()
                task {
                    api.login(address, secret)
                    email.text.clear()
                    statusText() + "\nRegion: ${api.config().country}. Startseite bei Bedarf neu laden."
                }
            }
        }
        profiles.setOnClickListener {
            task {
                val available = api.profiles()
                if (available.isEmpty()) throw ParamountException("Keine Profile verfügbar.")
                AlertDialog.Builder(activity).setTitle("Profil wählen")
                    .setItems(available.map { it.name }.toTypedArray()) { _, index ->
                        task { api.selectProfile(available[index].id); statusText() + "\nStartseite bitte neu laden." }
                    }.setNegativeButton("Abbrechen", null).show()
                statusText()
            }
        }
        logout.setOnClickListener { task { api.logout(); statusText() } }
        dialog.setOnDismissListener { password.text.clear(); scope.cancel() }
        dialog.show()
    }

    private fun statusText(): String = api.profileName?.let { "Angemeldet · Profil: $it" }
        ?: "Nicht angemeldet. Für die Wiedergabe ist ein direktes Paramount+-Abo erforderlich."
}
