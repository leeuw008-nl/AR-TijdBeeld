package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Temporary diagnostic placeholder: ARCore code is excluded from this APK. */
class ModelArActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "ARCore is tijdelijk uitgeschakeld voor de installatietest."
            textSize = 18f
            setPadding(32, 32, 32, 32)
        })
    }
}
