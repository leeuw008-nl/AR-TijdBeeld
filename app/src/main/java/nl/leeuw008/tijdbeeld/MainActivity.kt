package nl.leeuw008.tijdbeeld

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import org.json.JSONArray
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {
    private var session: Session? = null
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArCameraRenderer
    private lateinit var statusLabel: TextView
    private lateinit var directionArrow: TextView
    private lateinit var directionDistance: TextView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var headingDegrees: Float? = null
    private var installRequested = false
    private var photoIndex = -1
    private var photoId: String? = null
    private var initialImageWidthMeters = 3f

    private val buildingLocation = Location("TijdBeeld")
    private val photographerLocation = Location("TijdBeeld-fotograaf")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildingLocation.latitude = intent.getDoubleExtra("objectLat", 52.518349)
        buildingLocation.longitude = intent.getDoubleExtra("objectLng", 6.420889)
        photographerLocation.latitude = intent.getDoubleExtra("cameraLat", 52.518263)
        photographerLocation.longitude = intent.getDoubleExtra("cameraLng", 6.421917)
        photoIndex = intent.getIntExtra("photoIndex", -1)
        photoId = intent.getStringExtra("photoId")
        initialImageWidthMeters = intent.getFloatExtra("imageWidthMeters", 3f).coerceIn(1f, 12f)
        val imageUrl = intent.getStringExtra("imageUrl")
            ?: "https://oudommen-production.s3.eu-central-1.amazonaws.com/renditions/IO0042d1/large.jpg"
        val caption = intent.getStringExtra("title") ?: "1910 - Kantongerechtsgebouw"
        renderer = ArCameraRenderer().apply {
            setImageWidthMeters(initialImageWidthMeters)
            loadHistoricalImage(imageUrl, caption)
        }
        updateGeographicTarget()
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        statusLabel = TextView(this).apply {
            text = "TijdBeeld • camera starten…"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(5f, 1f, 1f, 0xFF000000.toInt())
            setPadding(20, 18, 160, 18)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setTextIsSelectable(true)
        }
        renderer.setStatusListener { message ->
            runOnUiThread { statusLabel.text = "TijdBeeld • $message" }
        }
        renderer.setDirectionListener { relativeBearing, distanceMeters ->
            runOnUiThread {
                val roundedDistance = distanceMeters.toInt().coerceAtLeast(0)
                directionDistance.text = "Fotolocatie • ${roundedDistance} m"
                directionArrow.rotation = relativeBearing
                val aligned = kotlin.math.abs(relativeBearing) <= 10f
                directionArrow.visibility = if (aligned) android.view.View.INVISIBLE else android.view.View.VISIBLE
                directionDistance.visibility = if (aligned) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF174A7E.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setBackgroundColor(0xFF1769AA.toInt())
        }
        header.addView(Button(this).apply {
            text = "‹"
            textSize = 30f
            minWidth = dp(48)
            minimumWidth = dp(48)
            setPadding(0, 0, 0, 0)
            contentDescription = "Terug"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(TextView(this).apply {
            text = "Bekijk via camera"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        val cameraArea = FrameLayout(this)
        cameraArea.addView(glView, FrameLayout.LayoutParams(-1, -1))
        cameraArea.addView(statusLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        val directionOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0x66000000)
        }
        directionArrow = TextView(this).apply {
            text = "↑"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(5f, 1f, 1f, 0xFF000000.toInt())
            contentDescription = "Richting naar historische fotolocatie"
        }
        directionDistance = TextView(this).apply {
            text = "Fotolocatie zoeken…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
        }
        directionOverlay.addView(directionArrow)
        directionOverlay.addView(directionDistance)
        cameraArea.addView(directionOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(58) })

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0x99000000.toInt())
        }
        val widthLabel = TextView(this).apply {
            text = String.format(Locale.getDefault(), "Breedte foto: %.1f m", initialImageWidthMeters)
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val widthSlider = SeekBar(this).apply {
            max = 110
            progress = ((initialImageWidthMeters - 1f) * 10f).toInt().coerceIn(0, 110)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val width = 1f + progress / 10f
                    renderer.setImageWidthMeters(width)
                    widthLabel.text = String.format(Locale.getDefault(), "Breedte foto: %.1f m", width)
                    savePhotoWidth(width)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val opacityLabel = TextView(this).apply {
            text = "Doorzichtigheid foto: 0%"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val opacitySlider = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    renderer.setImageAlpha(1f - progress / 100f)
                    opacityLabel.text = "Doorzichtigheid foto: ${progress}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        bottomControls.addView(widthLabel)
        bottomControls.addView(widthSlider)
        bottomControls.addView(opacityLabel)
        bottomControls.addView(opacitySlider)
        cameraArea.addView(bottomControls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(12)
        })
        root.addView(cameraArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        requestRequiredPermissions()
    }

    private fun savePhotoWidth(width: Float) {
        if (photoIndex < 0) return
        try {
            val prefs = getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE)
            val raw = prefs.getString("photos", null) ?: return
            val photos = JSONArray(raw)
            val targetIndex = photoId?.let { id ->
                (0 until photos.length()).firstOrNull { photos.getJSONObject(it).optString("id") == id }
            } ?: photoIndex.takeIf { it in 0 until photos.length() }
            if (targetIndex == null || targetIndex !in 0 until photos.length()) return
            photos.getJSONObject(targetIndex).put("imageWidthMeters", width.toDouble())
            prefs.edit().putString("photos", photos.toString()).commit()
        } catch (_: Exception) {
            // Keep camera rendering available even if a preference update fails.
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            statusLabel.text = "TijdBeeld • toestemming voor camera en locatie gevraagd"
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        startCameraSession()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun startCameraSession() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        renderer.setDisplayRotation(windowManager.defaultDisplay.rotation)
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val newSession = Session(this)
                if (!newSession.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                    newSession.close()
                    renderer.reportStatus("dit toestel ondersteunt ARCore Geospatial niet")
                    return
                }
                val geospatialConfig = newSession.config
                geospatialConfig.geospatialMode = Config.GeospatialMode.ENABLED
                newSession.configure(geospatialConfig)
                session = newSession
                renderer.setSession(newSession)
            }
            session?.resume()
            glView.onResume()
            renderer.reportStatus("camera en ARCore gestart")
        } catch (exception: UnavailableException) {
            Toast.makeText(this, "ARCore kan niet starten: ${exception.message}", Toast.LENGTH_LONG).show()
        } catch (exception: Exception) {
            Toast.makeText(this, "Camera starten mislukt: ${exception.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        headingDegrees = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateGeographicTarget() {
        // Face the historical image toward the saved photographer position.
        // Geospatial anchors use the East-Up-South coordinate frame.
        val bearingFromObjectToPhotographer = buildingLocation.bearingTo(photographerLocation)
        val yawRadians = Math.toRadians(180.0 - bearingFromObjectToPhotographer.toDouble()).toFloat()
        val halfYaw = yawRadians / 2f
        renderer.setGeospatialTarget(
            buildingLocation.latitude, buildingLocation.longitude, 1.65,
            0f, kotlin.math.sin(halfYaw), 0f, kotlin.math.cos(halfYaw)
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderer.setDisplayRotation(windowManager.defaultDisplay.rotation)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        glView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!cameraGranted) {
                statusLabel.text = "TijdBeeld • cameratoestemming ontbreekt"
                Toast.makeText(this, "Cameratoegang is nodig voor TijdBeeld.", Toast.LENGTH_LONG).show()
                return
            }
            if (!locationGranted) {
                Toast.makeText(this, "Locatietoestemming ontbreekt; de camera-preview kan wel starten.", Toast.LENGTH_LONG).show()
            }
            startCameraSession()
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 1001
    }
}
