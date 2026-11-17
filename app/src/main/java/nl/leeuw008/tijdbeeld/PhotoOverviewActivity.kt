package nl.leeuw008.tijdbeeld

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.view.Gravity
import android.view.View
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private data class CatalogPhoto(
    val id: String,
    val title: String,
    val imageUrl: String,
    val objectLat: Double,
    val objectLng: Double,
    val cameraLat: Double,
    val cameraLng: Double,
    val imageWidthMeters: Float
)

class PhotoOverviewActivity : Activity() {
    private lateinit var content: LinearLayout
    private var photos: List<CatalogPhoto> = emptyList()
    private var currentLocation: Location? = null
    private var catalogLoaded = false
    private var locationResolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildScreen()
            loadCatalog()
            resolveLocation()
        } catch (e: Exception) {
            Log.e("TijdBeeldOverview", "Foto-overzicht kon niet worden gestart", e)
            showStartupError(e)
        }
    }

    private fun showStartupError(error: Exception) {
        val message = TextView(this).apply {
            text = "Het foto-overzicht kon niet worden geopend.\n\n${error.javaClass.simpleName}: ${error.message ?: "onbekende fout"}"
            textSize = 14f
            setTextColor(0xFF9B1C1C.toInt())
            setPadding(24, 32, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(message) })
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(0xFFF3F6F9.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Historische foto's"
            textSize = 25f
            setTextColor(0xFF174A7E.toInt())
            setPadding(0, 0, 0, dp(5))
        })
        root.addView(TextView(this).apply {
            text = "De dichtstbijzijnde locaties staan bovenaan."
            textSize = 14f
            setTextColor(0xFF53616D.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "‹ Terug"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF174A7E.toInt())
            setPadding(0, dp(12), 0, dp(8))
            setOnClickListener { finish() }
        })
        setContentView(root)
        showStatus("Gepubliceerde foto's ophalen…")
    }

    private fun loadCatalog() {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(PUBLIC_CATALOG_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Catalogus niet bereikbaar (HTTP ${connection.responseCode})")
                }
                val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val array = root.getJSONArray("photos")
                val loaded = mutableListOf<CatalogPhoto>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val title = item.optString("title").trim()
                    val imageUrl = item.optString("imageUrl").trim()
                    val lat = item.optDouble("objectLat", Double.NaN)
                    val lng = item.optDouble("objectLng", Double.NaN)
                    if (title.isBlank() || imageUrl.isBlank() || !lat.isFinite() || !lng.isFinite()) continue
                    loaded.add(CatalogPhoto(
                        item.optString("id", "photo-$i"),
                        title, imageUrl, lat, lng,
                        item.optDouble("cameraLat", lat),
                        item.optDouble("cameraLng", lng),
                        item.optDouble("imageWidthMeters", 3.0).toFloat().coerceIn(1f, 12f)
                    ))
                }
                if (loaded.isEmpty()) throw IllegalStateException("Geen geldige gepubliceerde foto's gevonden")
                runOnUiThread {
                    photos = loaded
                    catalogLoaded = true
                    renderPhotos()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    content.removeAllViews()
                    addMessage("De fotocatalogus kon niet worden geladen. Controleer je internetverbinding en probeer het later opnieuw.")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun resolveLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        readLastLocation()
    }

    private fun readLastLocation() {
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { location ->
                    currentLocation = location
                    locationResolved = true
                    if (catalogLoaded) renderPhotos()
                }
                .addOnFailureListener {
                    locationResolved = true
                    if (catalogLoaded) renderPhotos()
                }
        } catch (e: Exception) {
            Log.w("TijdBeeldOverview", "Locatie kon niet worden opgehaald; overzicht blijft beschikbaar", e)
            locationResolved = true
            if (catalogLoaded) renderPhotos()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) readLastLocation()
            else {
                locationResolved = true
                if (catalogLoaded) renderPhotos()
            }
        }
    }

    private fun renderPhotos() {
        content.removeAllViews()
        val origin = currentLocation
        val sorted = if (origin != null) {
            photos.sortedBy { photo ->
                val result = FloatArray(1)
                Location.distanceBetween(origin.latitude, origin.longitude, photo.objectLat, photo.objectLng, result)
                result[0]
            }
        } else photos.sortedBy { it.title.lowercase(Locale.ROOT) }

        if (origin == null) {
            addMessage("Locatie niet beschikbaar. Geef locatietoestemming om de foto's op afstand te sorteren.")
        }
        sorted.forEach { photo ->
            val distanceLabel = origin?.let {
                val result = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, photo.objectLat, photo.objectLng, result)
                formatDistance(result[0])
            } ?: "Afstand onbekend"
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(9))
                setBackgroundColor(0xFFFFFFFF.toInt())
                elevation = dp(2).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { openPhoto(photo) }
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(190))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFE8EDF2.toInt())
                contentDescription = photo.title
            }
            item.addView(image)
            item.addView(TextView(this).apply {
                text = photo.title
                textSize = 13f
                setTextColor(0xFF263746.toInt())
                setPadding(dp(2), dp(6), dp(2), 0)
                maxLines = 2
            })
            item.addView(TextView(this).apply {
                text = distanceLabel
                textSize = 11f
                setTextColor(0xFF657585.toInt())
                setPadding(dp(2), dp(2), dp(2), 0)
            })
            loadThumbnail(photo.imageUrl, image)
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
        }
    }

    private fun loadThumbnail(url: String, target: ImageView) {
        Thread {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                }
                val bitmap = connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                connection.disconnect()
                if (bitmap != null) runOnUiThread {
                    if (!isFinishing && !isDestroyed) target.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.w("TijdBeeldOverview", "Miniatuur kon niet worden geladen: $url", e)
            }
        }.start()
    }

    private fun openPhoto(photo: CatalogPhoto) {
        if (photo.title.trim() == OMMERSCHANS_PHOTO_TITLE) {
            startActivity(Intent(this, ModelArActivity::class.java))
            return
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("title", photo.title)
            putExtra("imageUrl", photo.imageUrl)
            putExtra("objectLat", photo.objectLat)
            putExtra("objectLng", photo.objectLng)
            putExtra("cameraLat", photo.cameraLat)
            putExtra("cameraLng", photo.cameraLng)
            putExtra("imageWidthMeters", photo.imageWidthMeters)
            putExtra("photoId", photo.id)
        })
    }

    private fun showStatus(message: String) {
        content.removeAllViews()
        addMessage(message)
    }

    private fun addMessage(message: String) {
        content.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(0xFF53616D.toInt())
            setPadding(0, dp(12), 0, dp(12))
        })
    }

    private fun formatDistance(meters: Float): String =
        if (meters < 1000f) String.format(Locale.getDefault(), "%.0f m", meters)
        else String.format(Locale.getDefault(), "%.1f km", meters / 1000f)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 4102
        private const val PUBLIC_CATALOG_URL =
            "https://raw.githubusercontent.com/leeuw008-nl/TijdBeeld-data/main/photos.json"
    }
}
