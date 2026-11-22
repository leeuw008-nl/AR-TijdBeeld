package nl.leeuw008.tijdbeeld

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private lateinit var sensitivityControls: LinearLayout
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideSensitivityControls = Runnable {
        if (::sensitivityControls.isInitialized) {
            sensitivityControls.animate().alpha(0f).setDuration(180).withEndAction {
                sensitivityControls.visibility = View.GONE
            }.start()
        }
    }

    private fun showSensitivityControls() {
        if (!::sensitivityControls.isInitialized) return
        uiHandler.removeCallbacks(hideSensitivityControls)
        sensitivityControls.visibility = View.VISIBLE
        sensitivityControls.animate().alpha(1f).setDuration(120).start()
        uiHandler.postDelayed(hideSensitivityControls, 3000L)
    }
    private val standalonePreview: Boolean by lazy { intent.getBooleanExtra("standalone_preview", false) }
    private val photoTitle: String by lazy {
        if (standalonePreview) "3D-model bekijken" else
            intent.getStringExtra("photoTitle")?.takeIf { it.isNotBlank() } ?: OMMERSCHANS_PHOTO_TITLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = photoTitle + "\nCamera en geografische plaatsing starten…"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(5f, 1f, 1f, 0xFF000000.toInt())
            setPadding(dp(8), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER_VERTICAL
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
        if (standalonePreview) {
            var previousX = 0f
            var previousY = 0f
            var previousSpan = 0f
            var previousCenterX = 0f
            var previousCenterY = 0f
            var pinchActive = false
            fun span(event: MotionEvent): Float {
                if (event.pointerCount < 2) return 0f
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                return kotlin.math.sqrt(dx * dx + dy * dy)
            }
            glView.setOnTouchListener { _, event ->
                showSensitivityControls()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                        previousSpan = 0f
                        pinchActive = false
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        previousSpan = span(event)
                        previousCenterX = (event.getX(0) + event.getX(1)) / 2f
                        previousCenterY = (event.getY(0) + event.getY(1)) / 2f
                        pinchActive = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2) {
                            val currentSpan = span(event)
                            val centerX = (event.getX(0) + event.getX(1)) / 2f
                            val centerY = (event.getY(0) + event.getY(1)) / 2f
                            if (previousSpan > 0f && currentSpan > 0f) {
                                if (!pinchActive && kotlin.math.abs(currentSpan - previousSpan) > dp(8).toFloat()) pinchActive = true
                                if (pinchActive) {
                                    renderer.zoomStandalone(currentSpan / previousSpan)
                                } else {
                                    renderer.panStandalone(centerX - previousCenterX, centerY - previousCenterY, glView.width, glView.height)
                                }
                            }
                            previousSpan = currentSpan
                            previousCenterX = centerX
                            previousCenterY = centerY
                        } else {
                            val deltaX = event.x - previousX
                            val deltaY = event.y - previousY
                            renderer.rotateStandalone(deltaX, deltaY)
                            previousX = event.x
                            previousY = event.y
                        }
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        previousSpan = 0f
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        if (remaining < event.pointerCount) {
                            previousX = event.getX(remaining)
                            previousY = event.getY(remaining)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        previousSpan = 0f
                        uiHandler.removeCallbacks(hideSensitivityControls)
                        uiHandler.postDelayed(hideSensitivityControls, 3000L)
                        true
                    }
                    else -> true
                }
            }
        }
        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC102A43.toInt())
        }
        val backButton = TextView(this).apply {
            text = "←"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            contentDescription = "Terug"
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        if (standalonePreview) {
            val sensitivityLabel = TextView(this).apply {
                text = "Zoom-/sleepgevoeligheid: 100%"
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val sensitivitySlider = SeekBar(this).apply {
                max = 500
                progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        renderer.setZoomSensitivity(progress / 100f)
                        sensitivityLabel.text = "Zoom-/sleepgevoeligheid: $progress%"
                        if (fromUser) showSensitivityControls()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) { showSensitivityControls() }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        uiHandler.removeCallbacks(hideSensitivityControls)
                        uiHandler.postDelayed(hideSensitivityControls, 3000L)
                    }
                })
            }
            sensitivityControls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0xDD102A43.toInt())
                addView(sensitivityLabel, LinearLayout.LayoutParams(-1, -2))
                addView(sensitivitySlider, LinearLayout.LayoutParams(-1, -2))
                visibility = View.GONE
                alpha = 0f
                setOnTouchListener { _, event ->
                    showSensitivityControls()
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        uiHandler.removeCallbacks(hideSensitivityControls)
                        uiHandler.postDelayed(hideSensitivityControls, 3000L)
                    }
                    false
                }
            }
            root.addView(sensitivityControls, FrameLayout.LayoutParams(dp(280), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(24)
            })
        }
        setContentView(root)
        requestPermissionsIfNeeded()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

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
