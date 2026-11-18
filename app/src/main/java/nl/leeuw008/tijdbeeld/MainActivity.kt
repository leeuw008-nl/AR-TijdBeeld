package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Temporary diagnostic placeholder: ARCore code is excluded from this APK. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "TijdBeeld Diagnose\nARCore is tijdelijk uitgeschakeld voor de installatietest."
            textSize = 18f
            setPadding(32, 32, 32, 32)
        })
    }
}
