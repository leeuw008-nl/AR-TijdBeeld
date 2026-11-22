package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AdminLoginActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE) }
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hergebruik de lokaal opgeslagen beheersessie na app-herstart of terugkeer
        // naar het inlogscherm. De server controleert de token zodra Beheer opent.
        val savedToken = prefs.getString("admin_session_token", "").orEmpty()
        if (savedToken.isNotBlank()) {
            startActivity(Intent(this, AdminActivity::class.java)
                .putExtra("admin_authenticated", true))
            finish()
            return
        }

        buildLoginScreen()
    }

    private fun buildLoginScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(0xFFF3F6F9.toInt())
        }
        root.addView(TextView(this).apply {
            text = "AR-TijdBeeld • Beheerder inloggen"
            textSize = 23f
            setTextColor(0xFF174A7E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })
        root.addView(TextView(this).apply {
            text = "E-mailadres"
            textSize = 14f
            setTextColor(0xFF425466.toInt())
        })
        emailInput = EditText(this).apply {
            hint = "naam@voorbeeld.nl"
            setSingleLine(true)
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(prefs.getString("admin_email", "leeuw008@gmail.com") ?: "leeuw008@gmail.com")
        }
        root.addView(emailInput, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(14) })
        root.addView(TextView(this).apply {
            text = "Wachtwoord"
            textSize = 14f
            setTextColor(0xFF425466.toInt())
        })
        passwordInput = EditText(this).apply {
            hint = "Wachtwoord"
            setSingleLine(true)
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passwordInput, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(20) })
        root.addView(Button(this).apply {
            text = "Wachtwoord vergeten?"
            setOnClickListener { showForgotPasswordDialog() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
        root.addView(Button(this).apply {
            text = "Inloggen"
            textSize = 16f
            setOnClickListener { verifyCredentials() }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(Button(this).apply {
            text = "Terug"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        setContentView(root)
    }


    private fun showForgotPasswordDialog() {
        val field = EditText(this).apply {
            hint = "Beheerders e-mailadres"
            setSingleLine(true)
            setText(emailInput.text.toString().trim())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(this).setTitle("Wachtwoord vergeten?")
            .setMessage("We sturen een tijdelijke herstelcode naar het beheerdersadres.")
            .setView(field).setNegativeButton("Annuleren", null)
            .setPositiveButton("Verstuur code") { _, _ ->
                val address = field.text.toString().trim().lowercase()
                if (address.isBlank()) Toast.makeText(this, "Vul je e-mailadres in.", Toast.LENGTH_LONG).show()
                else requestPasswordReset(address)
            }.show()
    }

    private fun requestPasswordReset(email: String) {
        postAuthRequest("/auth/forgot-password", JSONObject().put("email", email)) { status, response ->
            if (status in 200..299 && response?.optBoolean("success") == true) showResetCodeDialog(email)
            else {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Aanvraag mislukt"
                val stage = response?.optString("diagnosticStage")?.takeIf { it.isNotBlank() }
                val detail = if (stage != null) "$error (fase: $stage, HTTP $status)" else "$error (HTTP $status)"
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResetCodeDialog(email: String) {
        val codeField = EditText(this).apply {
            hint = "Herstelcode (8 cijfers)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val passwordField = EditText(this).apply {
            hint = "Nieuw wachtwoord (minimaal 14 tekens)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(codeField)
            addView(passwordField)
        }
        AlertDialog.Builder(this).setTitle("Nieuw wachtwoord instellen")
            .setMessage("De herstelcode is 15 minuten geldig.")
            .setView(content).setNegativeButton("Annuleren", null)
            .setPositiveButton("Wachtwoord wijzigen") { _, _ ->
                val code = codeField.text.toString().trim()
                val password = passwordField.text.toString()
                if (!code.matches(Regex("\\d{8}")) || password.length < 14) {
                    Toast.makeText(this, "Controleer de code en gebruik minimaal 14 tekens voor het wachtwoord.", Toast.LENGTH_LONG).show()
                } else resetPassword(email, code, password)
            }.show()
    }

    private fun resetPassword(email: String, code: String, password: String) {
        postAuthRequest("/auth/reset-password",
            JSONObject().put("email", email).put("code", code).put("password", password)
        ) { status, response ->
            if (status in 200..299 && response?.optBoolean("success") == true) {
                passwordInput.text.clear()
                Toast.makeText(this, "Wachtwoord gewijzigd. Je kunt nu inloggen.", Toast.LENGTH_LONG).show()
            } else {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Wijzigen mislukt"
                val stage = response?.optString("diagnosticStage")?.takeIf { it.isNotBlank() }
                val detail = if (stage != null) "$error (fase: $stage, HTTP $status)" else "$error (HTTP $status)"
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun postAuthRequest(path: String, payload: JSONObject, callback: (Int, JSONObject?) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            var status = 0
            var response: JSONObject? = null
            try {
                connection = (URL("$WORKER_BASE_URL$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                response = runCatching { JSONObject(body) }.getOrNull()
            } catch (_: Exception) {
                response = JSONObject().put("error", "Netwerkfout. Controleer je internetverbinding.")
            } finally { connection?.disconnect() }
            runOnUiThread { callback(status, response) }
        }.start()
    }

    private fun verifyCredentials() {
        val email = emailInput.text.toString().trim().lowercase()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Vul je e-mailadres en wachtwoord in.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Inloggen…", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$WORKER_BASE_URL/auth/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject().put("email", email).put("password", password)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val response = runCatching { JSONObject(body) }.getOrNull()
                val token = response?.optString("token").orEmpty()
                val expiresIn = response?.optLong("expiresIn", 0L) ?: 0L
                runOnUiThread {
                    if (status in 200..299 && response?.optBoolean("success") == true && token.isNotBlank()) {
                        // Schrijf de beheersessie synchroon naar disk voordat het inlogscherm sluit.
                        // Zo blijft de token ook na direct afsluiten of herstart beschikbaar.
                        val sessionSaved = prefs.edit()
                            .putString("admin_email", email)
                            .putString("admin_session_token", token)
                            .putLong("admin_session_expires_at", System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L) // Eerste servercontrole na zeven dagen; server bewaart de sessie zonder KV-vervaldatum.
                            .commit()
                        if (sessionSaved) {
                            passwordInput.text.clear()
                            startActivity(Intent(this, AdminActivity::class.java).putExtra("admin_authenticated", true))
                            finish()
                        } else {
                            Toast.makeText(this, "De beheersessie kon niet lokaal worden opgeslagen. Probeer opnieuw.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val error = response?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "Inloggen is niet gelukt."
                        val stage = response?.optString("diagnosticStage")?.takeIf { it.isNotBlank() }
                        val message = if (stage != null) "$error (fase: $stage, HTTP $status)" else "$error (HTTP $status)"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Inloggen mislukt: ${e.message ?: "netwerkfout"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    companion object {
        private const val WORKER_BASE_URL = "https://tijdbeeld.leeuw008.workers.dev"
    }
}
