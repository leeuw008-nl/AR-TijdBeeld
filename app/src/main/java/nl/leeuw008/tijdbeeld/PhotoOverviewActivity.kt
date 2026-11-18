package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PhotoOverviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Diagnosetest: locatiebibliotheek tijdelijk uitgeschakeld."
            textSize = 18f
            setPadding(32, 32, 32, 32)
        })
    }
}
