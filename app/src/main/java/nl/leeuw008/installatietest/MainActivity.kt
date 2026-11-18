package nl.leeuw008.installatietest

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this).apply {
            text = "Installatie geslaagd\n\nDit is een minimale Android-testapp."
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        setContentView(message)
    }
}
