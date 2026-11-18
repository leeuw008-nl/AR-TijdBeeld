package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

open class DiagnosticPlaceholderActivity(private val message: String) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = message; textSize = 18f; setPadding(32,32,32,32) })
    }
}
class PhotoOverviewActivity : DiagnosticPlaceholderActivity("Foto-overzicht tijdelijk uitgeschakeld voor deze test.")
class PhotoMapActivity : DiagnosticPlaceholderActivity("Kaart tijdelijk uitgeschakeld voor deze test.")
class AdminLoginActivity : DiagnosticPlaceholderActivity("Beheer-login tijdelijk uitgeschakeld voor deze test.")
class AdminActivity : DiagnosticPlaceholderActivity("Beheer tijdelijk uitgeschakeld voor deze test.")
