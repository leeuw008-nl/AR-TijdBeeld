package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Public entry point for TijdBeeld.
 * Keep this screen independent from the AR renderer and administration tools.
 */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(0xFFF3F6F9.toInt())
        }

        root.addView(TextView(this).apply {
            text = "TijdBeeld"
            textSize = 32f
            setTextColor(0xFF174A7E.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Het verleden terug op zijn oorspronkelijke plek"
            textSize = 16f
            setTextColor(0xFF425466.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Ontdek historische foto's op de plek waar ze ooit zijn gemaakt."
            textSize = 17f
            setTextColor(0xFF263746.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "Bekijk foto-overzicht"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@StartActivity, PhotoOverviewActivity::class.java))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(12)
        })

        root.addView(Button(this).apply {
            text = "Bekijk voorbeeldfoto direct in AR"
            textSize = 15f
            setOnClickListener {
                startActivity(Intent(this@StartActivity, MainActivity::class.java).apply {
                    putExtra("title", "1910 - Kantongerechtsgebouw")
                    putExtra("imageUrl", "https://oudommen-production.s3.eu-central-1.amazonaws.com/renditions/IO0042d1/large.jpg")
                    putExtra("objectLat", 52.518349)
                    putExtra("objectLng", 6.420889)
                    putExtra("cameraLat", 52.518263)
                    putExtra("cameraLng", 6.421917)
                    putExtra("imageWidthMeters", 3f)
                })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(12)
        })

        root.addView(Button(this).apply {
            text = "Beheerder inloggen"
            textSize = 15f
            setOnClickListener {
                startActivity(Intent(this@StartActivity, AdminLoginActivity::class.java))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(12)
        })

        root.addView(Button(this).apply {
            text = "Bekijk alle foto's op de topografische kaart"
            textSize = 14f
            setOnClickListener {
                startActivity(Intent(this@StartActivity, PhotoMapActivity::class.java))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        root.addView(TextView(this).apply {
            text = "Beide overzichten gebruiken dezelfde gedeelde catalogus met gepubliceerde historische foto's."
            textSize = 13f
            setTextColor(0xFF657585.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }
}
