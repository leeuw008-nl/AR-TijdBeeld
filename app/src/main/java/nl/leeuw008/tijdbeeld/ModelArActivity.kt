package nl.leeuw008.tijdbeeld

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException

internal const val OMMERSCHANS_PHOTO_TITLE = "1900 - Ommerschans Hoofdgebouw"

/** Separate AR view for the Ommerschans GLB; the historical-photo flow is untouched. */
class ModelArActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: OmmerschansModelRenderer
    private lateinit var status: TextView
    private var session: Session? = null
    private var installRequested = false
    private var permissionRequested = false
    private val standalonePreview: Boolean by lazy { intent.getBooleanExtra("standalone_preview", false) }
    private val photoTitle: String by lazy {
        if (standalonePreview) "3D-model bekijken" else
            intent.getStringExtra("photoTitle")?.takeIf { it.isNotBlank() } ?: OMMERSCHANS_PHOTO_TITLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = photoTitle + "\nCamera en geografische plaatsing starten…"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(5f, 1f, 1f, 0xFF000000.toInt())
            setPadding(22, 20, 22, 20)
            setBackgroundColor(0xCC102A43.toInt())
            gravity = Gravity.CENTER
            maxLines = 3
            setTextIsSelectable(true)
        }
        renderer = OmmerschansModelRenderer(this, { message ->
            runOnUiThread { status.text = photoTitle + "\n" + message }
        }, standaloneMode = standalonePreview).apply {
            if (!standalonePreview) setGeoTarget(
                intent.getDoubleExtra("objectLat", 52.58737559898257),
                intent.getDoubleExtra("objectLng", 6.391727308884152)
            )
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        if (standalonePreview) return
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !permissionRequested) {
            permissionRequested = true
            requestPermissions(missing.toTypedArray(), 4102)
        }
    }

    override fun onResume() {
        super.onResume()
        renderer.setDisplayRotation(windowManager.defaultDisplay.rotation)
        if (standalonePreview) {
            glView.onResume()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val created = Session(this)
                if (!created.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                    created.close()
                    status.text = "Dit toestel ondersteunt ARCore Geospatial niet."
                    return
                }
                created.configure(created.config.apply { geospatialMode = Config.GeospatialMode.ENABLED })
                session = created
                renderer.setSession(created)
            }
            session?.resume()
            glView.onResume()
        } catch (e: UnavailableException) {
            status.text = "ARCore kan niet starten: ${e.message}"
        } catch (e: Exception) {
            status.text = "Starten mislukt: ${e.message}"
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderer.setDisplayRotation(windowManager.defaultDisplay.rotation)
    }

    override fun onPause() {
        glView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4102) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                onResume()
            } else {
                Toast.makeText(this, "Camera- en locatietoestemming zijn nodig voor de 3D-plaatsing.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
