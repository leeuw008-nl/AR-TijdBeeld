package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class HomePhoto(
    val id: String,
    val title: String,
    val imageUrl: String,
    val objectLat: Double,
    val objectLng: Double
)

class StartActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE) }
    private val CATALOG_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private lateinit var content: LinearLayout
    private lateinit var featuredImage: ImageView
    private lateinit var featuredTitle: TextView
    private lateinit var mapPreview: WebView
    private var photos: List<HomePhoto> = emptyList()
    private var featuredPhotoId: String = ""
    private var firstResume = true
    @Volatile private var sessionRefreshInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminSessionInBackground()
        if (firstResume) {
            firstResume = false
        } else if (::featuredImage.isInitialized) {
            // Bij terugkeer uit Beheer de actuele catalogus en lokale fotokeuze opnieuw tonen.
            loadCatalog()
        }
    }

    // Houd een bestaande beheersessie actief wanneer de gebruiker de app opnieuw opent.
    // Netwerkfouten veranderen de lokale aanmelding niet; alleen een geldige serverrespons
    // verlengt de lokale vervaldatum. Andere schermen en fotogegevens blijven onaangeraakt.
    private fun refreshAdminSessionInBackground() {
        val token = prefs.getString("admin_session_token", "").orEmpty()
        if (token.isBlank() || sessionRefreshInFlight) return
        sessionRefreshInFlight = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://tijdbeeld.leeuw008.workers.dev/auth/keep-session").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status in 200..299) {
                    val response = JSONObject(responseBody)
                    val persistent = response.optBoolean("persistent", false)
                    val expiresIn = response.optLong("expiresIn", 0L)
                    if (prefs.getString("admin_session_token", "").orEmpty() == token) {
                        prefs.edit().putLong(
                            "admin_session_expires_at",
                            if (persistent || expiresIn <= 0L) 0L else System.currentTimeMillis() + expiresIn * 1000L
                        ).apply()
                    }
                }
                // 401 wordt bewust niet lokaal afgehandeld op de startpagina.
                // Beheer controleert de sessie en toont daar zo nodig het inlogscherm.
            } catch (_: Exception) {
                // Geen netwerk: behoud de bestaande lokale sessiegegevens.
            } finally {
                connection?.disconnect()
                sessionRefreshInFlight = false
            }
        }.start()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(243, 246, 249))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(23, 105, 170))
        }
        val menu = TextView(this).apply {
            text = "☰"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Menu"
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setOnClickListener { showNavigationMenu(this) }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(TextView(this).apply {
            text = "AR-TijdBeeld"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "Selecteer AR-TijdBeeld via:"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 54, 75))
            setPadding(0, 0, 0, dp(16))
        })

        val photoCard = card()
        photoCard.addView(sectionTitle("1. Foto-overzicht"))
        featuredImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(224, 231, 237))
            contentDescription = "Geselecteerde historische foto"
        }
        photoCard.addView(featuredImage, LinearLayout.LayoutParams(-1, dp(170)))
        featuredTitle = TextView(this).apply {
            text = "Historische foto's"
            textSize = 14f
            setTextColor(Color.rgb(56, 72, 86))
            setPadding(dp(4), dp(8), dp(4), dp(10))
        }
        photoCard.addView(featuredTitle)
        photoCard.setOnClickListener {
            startActivity(Intent(this, PhotoOverviewActivity::class.java))
        }
        content.addView(photoCard)

        val mapCard = card()
        mapCard.addView(sectionTitle("2. Topo-kaart"))
        mapPreview = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            setBackgroundColor(Color.rgb(224, 231, 237))
        }
        // De kaart is een aanklikbare voorbeeldweergave; de WebView mag de tik niet onderscheppen.
        val mapPreviewFrame = FrameLayout(this).apply {
            addView(mapPreview, FrameLayout.LayoutParams(-1, -1))
            addView(android.view.View(this@StartActivity).apply {
                contentDescription = "Open topokaart"
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@StartActivity, PhotoMapActivity::class.java))
                }
            }, FrameLayout.LayoutParams(-1, -1))
        }
        mapCard.addView(mapPreviewFrame, LinearLayout.LayoutParams(-1, dp(180)))
        mapCard.addView(TextView(this).apply {
            text = "Bekijk alle historische fotolocaties op de kaart  ›"
            textSize = 14f
            setTextColor(Color.rgb(23, 105, 170))
            setPadding(dp(4), dp(10), dp(4), dp(8))
        })
        mapCard.setOnClickListener {
            startActivity(Intent(this, PhotoMapActivity::class.java))
        }
        content.addView(mapCard)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showNavigationMenu(anchor: android.view.View) {
        val adminPrefs = getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE)
        val sessionToken = adminPrefs.getString("admin_session_token", "").orEmpty()
        // Keep the locally stored admin token available throughout the test phase.
        // The Worker validates it when Beheer is opened.
        val adminSessionActive = sessionToken.isNotBlank()

        PopupMenu(this, anchor).apply {
            menu.add("Startpagina").setOnMenuItemClickListener { true }
            // Beheer is altijd gekoppeld aan een geldige serversessie.
            // Zonder token kan een foto alleen lokaal worden opgeslagen en verdwijnt
            // die bij herinstallatie. Daarom nooit lokaal beheer aanbieden alsof
            // publiceren mogelijk is: eerst veilig aanmelden bij de centrale catalogus.
            if (adminSessionActive) {
                val beheerMenu = menu.addSubMenu("Beheer")
                beheerMenu.add("Foto toevoegen").setOnMenuItemClickListener {
                    openAdminSection("add", false)
                    true
                }
                beheerMenu.add("Foto wijzigen").setOnMenuItemClickListener {
                    openAdminSection("edit", false)
                    true
                }
                beheerMenu.add("Foto startpagina").setOnMenuItemClickListener {
                    openAdminSection("home", false)
                    true
                }
                beheerMenu.add("Achtergrondkaart").setOnMenuItemClickListener {
                    chooseMapBackground()
                    true
                }
                beheerMenu.add("Uitloggen").setOnMenuItemClickListener {
                    logoutFromNavigationMenu()
                    true
                }
            } else {
                menu.add("Beheer inloggen").setOnMenuItemClickListener {
                    startActivity(Intent(this@StartActivity, AdminLoginActivity::class.java))
                    true
                }
            }
            menu.add("Back-up maken").setOnMenuItemClickListener {
                startActivity(Intent(this@StartActivity, BackupActivity::class.java))
                true
            }
            show()
        }
    }

    private fun chooseMapBackground() {
        val choices = arrayOf(
            "PDOK achtergrondkaart",
            "OpenStreetMap",
            "OpenTopoMap (topografisch met wandelpaden)"
        )
        val keys = arrayOf("pdok", "osm", "opentopo")
        val current = prefs.getString("map_background", "pdok")
        val selectedIndex = keys.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Achtergrondkaart voor de hele app")
            .setSingleChoiceItems(choices, selectedIndex) { dialog, which ->
                prefs.edit().putString("map_background", keys[which]).apply()
                dialog.dismiss()
                android.widget.Toast.makeText(this, "Achtergrondkaart opgeslagen", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun openAdminSection(section: String, localTestMode: Boolean) {
        startActivity(Intent(this, AdminActivity::class.java)
            .putExtra("admin_authenticated", true)
            .putExtra("local_test_mode", localTestMode)
            .putExtra("initial_section", section))
    }

    private fun logoutFromNavigationMenu() {
        val token = prefs.getString("admin_session_token", "").orEmpty()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                if (token.isNotBlank()) {
                    connection = (URL("https://tijdbeeld.leeuw008.workers.dev/auth/logout").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    connection.responseCode
                }
            } catch (_: Exception) {
                // Lokale sessie wordt ook zonder netwerkverbinding beëindigd.
            } finally {
                connection?.disconnect()
                prefs.edit().remove("admin_session_token").remove("admin_session_expires_at").apply()
            }
        }.start()
    }

    private fun loadCatalog() {
        val cachedBody = prefs.getString("public_catalog_json", null)
        if (!cachedBody.isNullOrBlank()) {
            applyCatalog(cachedBody)
            val cachedAt = prefs.getLong("public_catalog_fetched_at", 0L)
            if (System.currentTimeMillis() - cachedAt < CATALOG_CACHE_TTL_MS) return
        } else {
            loadLocalCatalogFallback()
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://tijdbeeld.leeuw008.workers.dev/photos").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Catalogus niet bereikbaar")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                prefs.edit()
                    .putString("public_catalog_json", body)
                    .putLong("public_catalog_fetched_at", System.currentTimeMillis())
                    .apply()
                runOnUiThread { applyCatalog(body) }
            } catch (_: Exception) {
                runOnUiThread {
                    if (photos.isEmpty()) {
                        featuredTitle.text = "De fotocatalogus kon niet worden geladen."
                        mapPreview.loadDataWithBaseURL(null, "<html><body style='font-family:sans-serif;color:#52616d;text-align:center;padding:24px'>Kaart niet beschikbaar</body></html>", "text/html", "UTF-8", null)
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun applyCatalog(body: String) {
        try {
            val root = JSONObject(body)
            val array = root.getJSONArray("photos")
            val loaded = mutableListOf<HomePhoto>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                loaded.add(HomePhoto(
                    item.optString("id"),
                    item.optString("title"),
                    item.optString("imageUrl"),
                    item.optDouble("objectLat"),
                    item.optDouble("objectLng")
                ))
            }
            // Een lokale keuze uit de proefapp heeft voorrang op een oudere servercache.
            val localFeaturedId = prefs.getString("local_featured_photo_id", "").orEmpty()
            if (localFeaturedId.isNotBlank()) {
                val localRaw = prefs.getString("photos", null)
                if (!localRaw.isNullOrBlank()) {
                    val localArray = org.json.JSONArray(localRaw)
                    for (i in 0 until localArray.length()) {
                        val item = localArray.getJSONObject(i)
                        if (item.optString("id") == localFeaturedId) {
                            val localPhoto = HomePhoto(
                                item.optString("id"),
                                item.optString("title"),
                                item.optString("imageUrl"),
                                item.optDouble("objectLat"),
                                item.optDouble("objectLng")
                            )
                            loaded.removeAll { it.id == localFeaturedId }
                            loaded.add(localPhoto)
                            break
                        }
                    }
                }
            }
            featuredPhotoId = localFeaturedId.takeIf { id -> loaded.any { it.id == id } }
                ?: root.optString("featuredPhotoId")
            photos = loaded
            showCatalog()
        } catch (_: Exception) {
            // Keep the last valid local display.
        }
    }

    private fun loadLocalCatalogFallback() {
        val raw = prefs.getString("photos", null) ?: return
        try {
            val array = org.json.JSONArray(raw)
            val local = mutableListOf<HomePhoto>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val imageUrl = item.optString("imageUrl")
                if (imageUrl.isBlank()) continue
                local.add(HomePhoto(
                    item.optString("id"), item.optString("title"), imageUrl,
                    item.optDouble("objectLat"), item.optDouble("objectLng")
                ))
            }
            photos = local
            featuredPhotoId = prefs.getString("local_featured_photo_id", "")
                ?.takeIf { it.isNotBlank() }
                ?: prefs.getString("featured_photo_id", "").orEmpty()
            if (photos.isNotEmpty()) showCatalog()
        } catch (_: Exception) {
            // No usable local catalog is available.
        }
    }

    private fun showCatalog() {
        if (photos.isEmpty()) return
        val featured = photos.firstOrNull { it.id == featuredPhotoId } ?: photos.first()
        featuredTitle.text = featured.title
        Thread {
            try {
                val bitmap = URL(featured.imageUrl).openStream().use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) runOnUiThread { featuredImage.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }.start()
        val markers = photos.joinToString(",") { "[${it.objectLat},${it.objectLng}]" }
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
            <style>html,body,#map{height:100%;margin:0} .leaflet-control-attribution{font-size:8px}</style>
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script></head>
            <body><div id="map"></div><script>
            const points=[$markers]; const map=L.map('map',{zoomControl:false,scrollWheelZoom:false,dragging:false, doubleClickZoom:false, touchZoom:false});
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
            points.forEach(p=>L.circleMarker(p,{radius:5,color:'#1769aa',fillColor:'#1769aa',fillOpacity:1}).addTo(map));
            if(points.length===1){map.setView(points[0],16)}else{map.fitBounds(points,{padding:[18,18],maxZoom:16})}
            </script></body></html>
        """.trimIndent()
        mapPreview.loadDataWithBaseURL("https://tijdbeeld.leeuw008.nl/", html, "text/html", "UTF-8", null)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(220, 228, 235))
        }
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        layoutParams = params
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(23, 74, 126))
        setPadding(dp(2), dp(2), dp(2), dp(10))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
