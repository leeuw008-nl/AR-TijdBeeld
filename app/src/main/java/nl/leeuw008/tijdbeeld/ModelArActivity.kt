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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
    private lateinit var modelControls: LinearLayout
    private var pivotXProgress = 50
    private var pivotYProgress = 50
    private var pivotZProgress = 50
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideSensitivityControls = Runnable {
        if (::sensitivityControls.isInitialized) {
            sensitivityControls.animate().alpha(0f).setDuration(160).withEndAction {
                sensitivityControls.visibility = View.GONE
            }.start()
        }
        if (::modelControls.isInitialized) {
            modelControls.animate().alpha(0f).setDuration(160).withEndAction {
                modelControls.visibility = View.GONE
            }.start()
        }
    }

    private fun showSensitivityControls() {
        if (!::sensitivityControls.isInitialized || !::modelControls.isInitialized) return
        uiHandler.removeCallbacks(hideSensitivityControls)
        sensitivityControls.visibility = View.VISIBLE
        modelControls.visibility = View.VISIBLE
        sensitivityControls.animate().alpha(1f).setDuration(100).start()
        modelControls.animate().alpha(1f).setDuration(100).start()
        uiHandler.postDelayed(hideSensitivityControls, 2000L)
    }
    private val standalonePreview: Boolean by lazy { intent.getBooleanExtra("standalone_preview", false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Camera en geografische plaatsing starten…"
            textSize = 12f
            setTextColor(0xFF263746.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setTextIsSelectable(true)
            visibility = if (standalonePreview) View.GONE else View.VISIBLE
        }
        renderer = OmmerschansModelRenderer(
            this,
            { message -> runOnUiThread { status.text = message } },
            standaloneMode = standalonePreview,
            modelUrl = intent.getStringExtra("modelUrl")?.takeIf { it.isNotBlank() },
            modelAsset = intent.getStringExtra("modelAsset")?.takeIf { it.isNotBlank() } ?: "ommerschans.glb"
        ).apply {
            if (!standalonePreview) setGeoTarget(
                intent.getDoubleExtra("targetLat", intent.getDoubleExtra("objectLat", 52.58737559898257)),
                intent.getDoubleExtra("targetLng", intent.getDoubleExtra("objectLng", 6.391727308884152))
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
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        previousSpan = span(event)
                        previousCenterX = (event.getX(0) + event.getX(1)) / 2f
                        previousCenterY = (event.getY(0) + event.getY(1)) / 2f
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2) {
                            val currentSpan = span(event)
                            val centerX = (event.getX(0) + event.getX(1)) / 2f
                            val centerY = (event.getY(0) + event.getY(1)) / 2f
                            if (previousSpan > 0f && currentSpan > 0f) {
                                // Tweevingerbeweging verplaatst het model; afstandsverandering zoomt tegelijk.
                                val spanDelta = currentSpan - previousSpan
                                if (kotlin.math.abs(spanDelta) > 0.35f) {
                                    renderer.zoomStandalone(currentSpan / previousSpan)
                                }
                                renderer.panStandalone(
                                    centerX - previousCenterX,
                                    centerY - previousCenterY,
                                    glView.width,
                                    glView.height
                                )
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
                        uiHandler.postDelayed(hideSensitivityControls, 2000L)
                        true
                    }
                    else -> true
                }
            }
        }
        val root = FrameLayout(this)
        glView.isFocusable = false
        glView.isFocusableInTouchMode = false
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setBackgroundColor(0xFF1769AA.toInt())
        }
        val backButton = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            contentDescription = "Terug"
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "AR-TijdBeeld"
            textSize = 21f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (!standalonePreview) {
            root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                topMargin = dp(64)
            })
        }

        if (standalonePreview) {
            val sensitivityLabel = TextView(this).apply {
                text = "Zoom-/sleepgevoeligheid: 100%"
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val sensitivitySlider = SeekBar(this).apply {
                max = 10000
                progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        renderer.setZoomSensitivity(progress / 100f)
                        sensitivityLabel.text = "Zoom-/sleepgevoeligheid: $progress%"
                        if (fromUser) showSensitivityControls()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        uiHandler.removeCallbacks(hideSensitivityControls)
                        sensitivityControls.visibility = View.VISIBLE
                        sensitivityControls.alpha = 1f
                    }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        uiHandler.removeCallbacks(hideSensitivityControls)
                        uiHandler.postDelayed(hideSensitivityControls, 2000L)
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
                        uiHandler.postDelayed(hideSensitivityControls, 2000L)
                    }
                    false
                }
            }
            root.addView(sensitivityControls, FrameLayout.LayoutParams(dp(280), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(116)
            })

            fun controlButton(label: String, action: () -> Unit): TextView =
                TextView(this).apply {
                    text = label
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xDD102A43.toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        action()
                        showSensitivityControls()
                    }
                }
            modelControls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(5), dp(6), dp(5))
                setBackgroundColor(0x66000000)
                visibility = View.GONE
                alpha = 0f
            }
            val orbitRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            listOf(
                "◀" to { renderer.stepOrbitStandalone(-15f, 0f) },
                "▲" to { renderer.stepOrbitStandalone(0f, -12f) },
                "▶" to { renderer.stepOrbitStandalone(15f, 0f) },
                "▼" to { renderer.stepOrbitStandalone(0f, 12f) }
            ).forEach { (label, action) ->
                orbitRow.addView(controlButton(label, action), LinearLayout.LayoutParams(dp(48), dp(40)).apply {
                    leftMargin = dp(3); rightMargin = dp(3); bottomMargin = dp(4)
                })
            }
            modelControls.addView(orbitRow)
            val zoomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            zoomRow.addView(controlButton("−", { renderer.zoomStandalone(0.8f) }),
                LinearLayout.LayoutParams(dp(56), dp(38)).apply { rightMargin = dp(4) })
            zoomRow.addView(controlButton("Reset", { renderer.resetStandaloneView() }).apply {
                textSize = 14f
            }, LinearLayout.LayoutParams(dp(92), dp(38)).apply { leftMargin = dp(4); rightMargin = dp(4) })
            zoomRow.addView(controlButton("+", { renderer.zoomStandalone(1.25f) }),
                LinearLayout.LayoutParams(dp(56), dp(38)).apply { leftMargin = dp(4) })
            modelControls.addView(zoomRow)
            modelControls.addView(controlButton("Draaipunt verplaatsen") {
                showPivotDialog()
            }, LinearLayout.LayoutParams(dp(220), dp(38)).apply {
                topMargin = dp(5)
            })
            root.addView(modelControls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(10)
            })
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        setContentView(root)
        requestPermissionsIfNeeded()
    }

    private fun showPivotDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun addAxis(label: String, initial: Int, update: (Int) -> Unit) {
            val valueLabel = TextView(this).apply {
                text = "$label: $initial%"
                textSize = 14f
                setTextColor(0xFF243746.toInt())
            }
            val slider = SeekBar(this).apply {
                max = 100
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueLabel.text = "$label: $progress%"
                        update(progress)
                        renderer.setStandalonePivot(pivotXProgress, pivotYProgress, pivotZProgress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            content.addView(valueLabel, LinearLayout.LayoutParams(-1, -2))
            content.addView(slider, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
        addAxis("Links/rechts (X)", pivotXProgress) { pivotXProgress = it }
        addAxis("Hoogte (Y)", pivotYProgress) { pivotYProgress = it }
        addAxis("Voor/achter (Z)", pivotZProgress) { pivotZProgress = it }
        android.app.AlertDialog.Builder(this)
            .setTitle("Draaipunt van het 3D-model")
            .setMessage("Verplaats het draaipunt binnen het model. 50% op alle assen is het oorspronkelijke middelpunt.")
            .setView(content)
            .setPositiveButton("Gereed", null)
            .setNeutralButton("Herstel midden") { _, _ ->
                pivotXProgress = 50
                pivotYProgress = 50
                pivotZProgress = 50
                renderer.setStandalonePivot(50, 50, 50)
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (standalonePreview) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> renderer.stepOrbitStandalone(-12f, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> renderer.stepOrbitStandalone(12f, 0f)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> renderer.stepOrbitStandalone(0f, -10f)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> renderer.stepOrbitStandalone(0f, 10f)
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> renderer.zoomStandalone(1.2f)
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> renderer.zoomStandalone(0.83f)
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_R -> renderer.resetStandaloneView()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
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
