package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.util.UUID
import java.util.Locale

data class PhotoConfig(
    var title: String,
    var imageUrl: String,
    var objectLat: Double,
    var objectLng: Double,
    var cameraLat: Double,
    var cameraLng: Double,
    var imageWidthMeters: Float = 3f,
    var id: String = UUID.randomUUID().toString(),
    var source: String = "",
    var sourceId: String = "",
    var published: Boolean = false,
    var imageHeightMeters: Float = 1.65f,
    var objectType: String = "PHOTO",
    var modelUrl: String = "",
    var modelAsset: String = "",
    var testLat: Double = Double.NaN,
    var testLng: Double = Double.NaN,
    var displayLocation: String = "OBJECT",
    var modelScale: Float = 1f
) {
    fun caption() = title
}

class AdminActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE) }
    private val CATALOG_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private val photos = mutableListOf<PhotoConfig>()
    private var selected = 0
    private lateinit var titleInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var objectLatInput: EditText
    private lateinit var objectLngInput: EditText
    private lateinit var cameraLatInput: EditText
    private lateinit var cameraLngInput: EditText
    private lateinit var publishCheckbox: CheckBox
    private lateinit var objectTypeSelector: Spinner
    private lateinit var testLocationSelector: Spinner
    private lateinit var testLatInput: EditText
    private lateinit var testLngInput: EditText
    private lateinit var cameraFieldsContainer: LinearLayout
    private lateinit var testFieldsContainer: LinearLayout
    private lateinit var map: WebView
    private var adminScreenView: View? = null
    private var mapScreenActive = false
    private lateinit var preview: ImageView
    private lateinit var featuredPreview: ImageView
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val thumbnailLoading = mutableSetOf<String>()
    private lateinit var photoSelector: Spinner
    private lateinit var featuredSelector: Spinner
    private var featuredPhotoId: String = ""
    private var sessionToken: String = ""
    private var updatingFields = false
    private var updatingFeaturedSelector = false
    private var pendingFeaturedPhotoId: String = ""
    private var mapReady = false
    private var returningFromCamera = false
    private var hasLocalEditsSinceLaunch = false
    private var currentSection: String = "edit"
    private var localTestMode: Boolean = false
    private var modelPreviewButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("admin_authenticated", false).not()) {
            finish()
            return
        }
        sessionToken = prefs.getString("admin_session_token", "").orEmpty()
        localTestMode = packageName.endsWith(".proef") &&
            intent.getBooleanExtra("local_test_mode", false)
        if (sessionToken.isBlank() && !localTestMode) {
            finish()
            return
        }
        currentSection = intent.getStringExtra("initial_section")
            ?.takeIf { it == "add" || it == "edit" || it == "home" } ?: "edit"
        featuredPhotoId = prefs.getString("featured_photo_id", "").orEmpty()
        if (!loadPhotos()) {
            setContentView(TextView(this).apply {
                text = "De opgeslagen fotogegevens konden niet veilig worden gelezen. Er is niets overschreven. Sluit deze app en bewaar de appgegevens; herstel is nodig voordat Beheer weer kan worden geopend."
                textSize = 16f
                setPadding(32, 48, 32, 32)
            })
            return
        }
        buildScreen()
        if (currentSection == "add") addPhoto(skipSave = true)
        else if (currentSection != "home") showPhoto(0)
        loadPublicCatalog()
    }

    private fun keepSessionAlive() {
        // Lokale proefmodus heeft bewust geen servertoken; stuur dan geen keep-alive verzoek.
        if (sessionToken.isBlank() || packageName.endsWith(".proef")) return
        Thread {
            var connection: HttpURLConnection? = null
            var status = 0
            var responseBody = ""
            try {
                connection = (URL("$WORKER_BASE_URL/auth/keep-session").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $sessionToken")
                }
                status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } catch (_: Exception) {
                // Keep the local session during temporary network problems.
            } finally {
                connection?.disconnect()
            }
            if (status == 401) {
                prefs.edit().remove("admin_session_token").remove("admin_session_expires_at").remove("admin_session_last_refresh_at").commit()
                runOnUiThread {
                    Toast.makeText(this, "De beheersessie is ingetrokken. Log opnieuw in.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, AdminLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }
            } else if (status in 200..299) {
                val response = runCatching { JSONObject(responseBody) }.getOrNull()
                val expiresIn = response?.optLong("expiresIn", 0L) ?: 0L
                val now = System.currentTimeMillis()
                val durationMs = (expiresIn.takeIf { it > 0L } ?: 30L * 24L * 60L * 60L) * 1000L
                prefs.edit()
                    .putLong("admin_session_expires_at", now + durationMs)
                    .putLong("admin_session_last_refresh_at", now)
                    .apply()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        keepSessionAlive()
        if (returningFromCamera) {
            returningFromCamera = false
            hasLocalEditsSinceLaunch = true
            if (!loadPhotos()) {
                Toast.makeText(this, "De opgeslagen fotogegevens konden niet veilig worden gelezen. De bestaande gegevens zijn niet overschreven.", Toast.LENGTH_LONG).show()
                return
            }
            showPhoto(selected.coerceIn(0, photos.lastIndex))
        }
    }

    private fun loadPhotos(): Boolean {
        // Parse into a temporary list. Never clear or overwrite the stored list until
        // the complete JSON has been read successfully.
        val loadedPhotos = mutableListOf<PhotoConfig>()
        var raw = try {
            prefs.getString("photos", null)
        } catch (_: Exception) {
            return false
        }
        var restoredFromBackup = false
        if (raw != null) {
            val primaryIsUsable = try {
                JSONArray(raw).length() > 0
            } catch (_: Exception) { false }
            if (!primaryIsUsable) {
                val backup = prefs.getString("photos_backup", null)
                val backupIsUsable = try {
                    !backup.isNullOrBlank() && JSONArray(backup).length() > 0
                } catch (_: Exception) { false }
                if (!backupIsUsable) return false
                raw = backup
                restoredFromBackup = true
            }
        } else {
            // A missing primary key is not proof of a first install: the previous
            // save may have been interrupted after the backup was written.
            val backup = prefs.getString("photos_backup", null)
            if (backup != null) {
                if (backup.isBlank()) return false
                raw = backup
                restoredFromBackup = true
            }
        }

        if (raw != null) {
            try {
                val array = JSONArray(raw)
                if (array.length() == 0) return false
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    loadedPhotos.add(PhotoConfig(
                        o.optString("title"), o.optString("imageUrl"),
                        o.optDouble("objectLat", 52.518349), o.optDouble("objectLng", 6.420889),
                        o.optDouble("cameraLat", 52.518263), o.optDouble("cameraLng", 6.421917),
                        o.optDouble("imageWidthMeters", 3.0).toFloat().coerceIn(1f, 12f),
                        o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        o.optString("source"), o.optString("sourceId"), o.optBoolean("published", false),
                        o.optDouble("imageHeightMeters", 1.65).toFloat().coerceIn(1f, 3f),
                        o.optString("type", "PHOTO").uppercase(Locale.ROOT), o.optString("modelUrl"), o.optString("modelAsset"),
                        o.optDouble("testLat", Double.NaN), o.optDouble("testLng", Double.NaN),
                        o.optString("displayLocation", "OBJECT"), o.optDouble("modelScale", 1.0).toFloat().coerceIn(0.01f, 100f)
                    ))
                }
            } catch (_: Exception) {
                return false
            }
            if (loadedPhotos.isEmpty()) return false
        } else {
            // Only a genuinely first-time installation may create the starter photo.
            loadedPhotos.add(PhotoConfig(
                "1910 - Kantongerechtsgebouw",
                "https://oudommen-production.s3.eu-central-1.amazonaws.com/renditions/IO0042d1/large.jpg",
                52.518349, 6.420889, 52.518263, 6.421917, 3f
            ))
        }

        photos.clear()
        photos.addAll(loadedPhotos)
        if (raw == null || restoredFromBackup) saveAll()
        return true
    }
    private fun loadPublicCatalog() {
        val cachedBody = prefs.getString("public_catalog_json", null)
        if (!cachedBody.isNullOrBlank()) {
            applyPublicCatalog(cachedBody)
            val cachedAt = prefs.getLong("public_catalog_fetched_at", 0L)
            if (System.currentTimeMillis() - cachedAt < CATALOG_CACHE_TTL_MS) return
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(WORKER_BASE_URL + "/photos").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Catalogus HTTP ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                prefs.edit()
                    .putString("public_catalog_json", body)
                    .putLong("public_catalog_fetched_at", System.currentTimeMillis())
                    .apply()
                runOnUiThread { applyPublicCatalog(body) }
            } catch (_: Exception) {
                // Continue with the catalog already stored locally; do not retry repeatedly.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun applyPublicCatalog(body: String) {
        try {
            val root = JSONObject(body)
            val array = root.getJSONArray("photos")
            val remoteFeaturedPhotoId = root.optString("featuredPhotoId")
            val remotePhotos = mutableListOf<PhotoConfig>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                remotePhotos.add(PhotoConfig(
                    o.optString("title"),
                    o.optString("imageUrl"),
                    o.optDouble("objectLat", 52.518349),
                    o.optDouble("objectLng", 6.420889),
                    o.optDouble("cameraLat", 52.518263),
                    o.optDouble("cameraLng", 6.421917),
                    o.optDouble("imageWidthMeters", 3.0).toFloat().coerceIn(1f, 12f),
                    o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    o.optString("source"), o.optString("sourceId"), true,
                    o.optDouble("imageHeightMeters", 1.65).toFloat().coerceIn(1f, 3f),
                    o.optString("type", "PHOTO").uppercase(Locale.ROOT), o.optString("modelUrl"), o.optString("modelAsset"),
                    o.optDouble("testLat", Double.NaN), o.optDouble("testLng", Double.NaN),
                    o.optString("displayLocation", "OBJECT"), o.optDouble("modelScale", 1.0).toFloat().coerceIn(0.01f, 100f)
                ))
            }
            if (remotePhotos.isEmpty()) return
            if (!hasLocalEditsSinceLaunch) {
                val localOnly = photos.filter { local ->
                    remotePhotos.none { remote ->
                        remote.id == local.id ||
                            (local.imageUrl.isNotBlank() && remote.imageUrl == local.imageUrl)
                    }
                }
                val keepLocalFeatured = photos.any { it.id == featuredPhotoId }
                photos.clear()
                photos.addAll(remotePhotos)
                if (!keepLocalFeatured) {
                    featuredPhotoId = remoteFeaturedPhotoId.ifBlank { remotePhotos.first().id }
                }
                pendingFeaturedPhotoId = featuredPhotoId
                prefs.edit().putString("featured_photo_id", featuredPhotoId).apply()
                photos.addAll(localOnly)
                selected = selected.coerceIn(0, photos.lastIndex)
                saveAll()
                refreshFeaturedSelector()
                if (currentSection != "home" && ::titleInput.isInitialized) showPhoto(selected)
            }
        } catch (_: Exception) {
            // Ignore an invalid cached catalog and keep the local administration data.
        }
    }

    private fun buildScreen() {
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setBackgroundColor(0xFF1769AA.toInt())
        }
        heading.addView(TextView(this).apply {
            text = "☰"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            contentDescription = "Menu"
            setPadding(dp(8), dp(4), dp(16), dp(4))
            setOnClickListener { anchor ->
                PopupMenu(this@AdminActivity, anchor).apply {
                    menu.add("Startpagina").setOnMenuItemClickListener {
                        finish()
                        true
                    }
                    val beheerMenu = menu.addSubMenu("Beheer")
                    beheerMenu.add("Object toevoegen").setOnMenuItemClickListener {
                        switchSection("add")
                        true
                    }
                    beheerMenu.add("Object wijzigen").setOnMenuItemClickListener {
                        switchSection("edit")
                        true
                    }
                    beheerMenu.add("Startpagina-object").setOnMenuItemClickListener {
                        switchSection("home")
                        true
                    }
                    beheerMenu.add("Achtergrondkaart").setOnMenuItemClickListener {
                        chooseMapBackground()
                        true
                    }
                    beheerMenu.add("Back-up en herstel").setOnMenuItemClickListener {
                        startActivity(Intent(this@AdminActivity, BackupActivity::class.java))
                        true
                    }
                    beheerMenu.add("Uitloggen").setOnMenuItemClickListener {
                        logout()
                        true
                    }
                    show()
                }
            }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        heading.addView(TextView(this).apply {
            text = "AR-TijdBeeld"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }

        if (currentSection == "home") {
            addSection(root, "Selecteer het object voor de startpagina")
            featuredSelector = Spinner(this)
            root.addView(featuredSelector, LinearLayout.LayoutParams(-1, dp(52)))
            root.addView(View(this).apply {
                setBackgroundColor(0xFF1769AA.toInt())
            }, LinearLayout.LayoutParams(-1, dp(3)).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            })
            featuredPreview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFFE8EDF2.toInt())
                contentDescription = "Geselecteerde startpaginafoto"
                setOnClickListener {
                    showFullscreenImage(this, photos.firstOrNull { it.id == pendingFeaturedPhotoId }?.title.orEmpty())
                }
            }
            root.addView(featuredPreview, LinearLayout.LayoutParams(-1, dp(180)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            })
            pendingFeaturedPhotoId = featuredPhotoId
            refreshFeaturedSelector()
            root.addView(actionButton("Opslaan startpaginafoto") { saveFeaturedPhoto() },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
            root.addView(TextView(this).apply {
                text = "Alle opgeslagen foto's zijn beschikbaar. Een foto die nog niet gepubliceerd is, wordt pas zichtbaar voor bezoekers nadat je deze ook publiceert."
                textSize = 13f
                setTextColor(0xFF5B6873.toInt())
                setPadding(0, dp(10), 0, dp(4))
            })
        } else {
            val formPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (currentSection == "edit") {
                photoSelector = Spinner(this)
                root.addView(photoSelector, LinearLayout.LayoutParams(-1, dp(52)))
                // Blauwe selectielijn: sluit aan op de headerkleur.
                root.addView(View(this).apply {
                    setBackgroundColor(0xFF1769AA.toInt())
                }, LinearLayout.LayoutParams(-1, dp(3)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(8)
                })
            }

            preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setBackgroundColor(0xFFE8EDF2.toInt())
                minimumHeight = dp(90)
                maxHeight = dp(190)
                contentDescription = "Foto openen op volledig scherm"
                setOnClickListener { showFullscreenImage(this, titleInput.text.toString()) }
            }
            formPanel.addView(preview, LinearLayout.LayoutParams(-1, dp(190)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            })
            addSection(formPanel, "Type object")
            objectTypeSelector = Spinner(this)
            objectTypeSelector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Foto", "3D-model"))
            formPanel.addView(objectTypeSelector, LinearLayout.LayoutParams(-1, dp(48)))
            titleInput = addField(formPanel, "Titel / bijschrift", "Bijv. 1910 - Kantongerechtsgebouw").apply {
                setSelectAllOnFocus(true)
            }
            urlInput = addField(formPanel, "URL van de foto of het 3D-modelbestand (GLB)", "https://…")
            // Foto controleren is samengevoegd met Opslaan.

            addSection(formPanel, "Locatie van het object")
            val objectCoordinates = coordinateRow(formPanel, "Breedtegraad (latitude)", "52.518349", "Lengtegraad (longitude)", "6.420889")
            objectLatInput = objectCoordinates.first
            objectLngInput = objectCoordinates.second
            cameraFieldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            addSection(cameraFieldsContainer, "Locatie waar de foto is gemaakt")
            val cameraCoordinates = coordinateRow(cameraFieldsContainer, "Breedtegraad (latitude)", "52.518263", "Lengtegraad (longitude)", "6.421917")
            cameraLatInput = cameraCoordinates.first
            cameraLngInput = cameraCoordinates.second
            formPanel.addView(cameraFieldsContainer)
            testFieldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            addSection(testFieldsContainer, "Testlocatie van het object")
            val testCoordinates = coordinateRow(testFieldsContainer, "Breedtegraad (latitude)", "52.518349", "Lengtegraad (longitude)", "6.420889")
            testLatInput = testCoordinates.first
            testLngInput = testCoordinates.second
            addSection(testFieldsContainer, "Weergavelocatie 3D-model")
            testLocationSelector = Spinner(this)
            testLocationSelector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Locatie van het object", "Testlocatie"))
            testFieldsContainer.addView(testLocationSelector, LinearLayout.LayoutParams(-1, dp(48)))
            formPanel.addView(testFieldsContainer)
            addSection(formPanel, "Kaart en camerabeeld")
            if (currentSection == "edit") {
                val mapCameraActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                mapCameraActions.addView(actionButton("Open topo kaart") { openMapPage() },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(3) })
                val previewButton = actionButton("Bekijk 3D-model") {
                    saveCurrent()
                    photos.getOrNull(selected)?.let { item ->
                        if (item.objectType == "MODEL") startActivity(Intent(this@AdminActivity, ModelArActivity::class.java).apply {
                            putExtra("standalone_preview", true)
                            putExtra("photoTitle", item.title)
                            putExtra("modelUrl", item.modelUrl)
                            putExtra("modelAsset", item.modelAsset)
                        })
                    }
                }.apply {
                    textSize = 11f
                    minWidth = 0
                    setPadding(dp(3), 0, dp(3), 0)
                    visibility = if (photos.getOrNull(selected)?.objectType == "MODEL") View.VISIBLE else View.GONE
                }
                modelPreviewButton = previewButton
                mapCameraActions.addView(previewButton,
                    LinearLayout.LayoutParams(dp(112), dp(44)).apply {
                        marginStart = dp(3); marginEnd = dp(3)
                    })
                mapCameraActions.addView(actionButton("Bekijk via camera") { saveCurrent(); openCamera() },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3) })
                formPanel.addView(mapCameraActions, LinearLayout.LayoutParams(-1, dp(44)).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(6)
                })

                val saveDeleteActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                saveDeleteActions.addView(actionButton("Opslaan") {
                    val imageUrl = urlInput.text.toString().trim()
                    if (imageUrl.isBlank()) {
                        Toast.makeText(this@AdminActivity, "Vul eerst de URL van de foto in.", Toast.LENGTH_LONG).show()
                        return@actionButton
                    }
                    saveCurrent()
                    if (photos[selected].published) publishCatalog()
                    loadPreview(imageUrl, showMessage = true)
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
                saveDeleteActions.addView(actionButton("Verwijderen") { removePhoto() },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
                formPanel.addView(saveDeleteActions, LinearLayout.LayoutParams(-1, dp(48)))
            } else {
                formPanel.addView(actionButton("Open topo kaart") { openMapPage() },
                    LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10); bottomMargin = dp(6) })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(actionButton("Opslaan") {
                    val imageUrl = urlInput.text.toString().trim()
                    if (imageUrl.isBlank()) {
                        Toast.makeText(this@AdminActivity, "Vul eerst de URL van de foto in.", Toast.LENGTH_LONG).show()
                        return@actionButton
                    }
                    saveCurrent()
                    if (photos[selected].published) publishCatalog()
                    loadPreview(imageUrl, showMessage = true)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                actions.addView(actionButton("Bekijk via camera") { saveCurrent(); openCamera() },
                    LinearLayout.LayoutParams(0, dp(48), 1f))
                formPanel.addView(actions)
            }

            addSection(formPanel, "Publiceren voor iedereen")
            publishCheckbox = CheckBox(this).apply {
                text = "Publiceer dit object voor iedereen"
                textSize = 14f
                setTextColor(0xFF263746.toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (!updatingFields && photos.isNotEmpty()) {
                        photos[selected].published = checked
                        hasLocalEditsSinceLaunch = true
                        if (!checked && featuredPhotoId == photos[selected].id) {
                            featuredPhotoId = photos.firstOrNull { it.published && it.id != photos[selected].id }?.id.orEmpty()
                        }
                        saveAll()
                        if (::featuredSelector.isInitialized) refreshFeaturedSelector()
                        publishCatalog()
                    }
                }
            }
            formPanel.addView(publishCheckbox)
            formPanel.addView(TextView(this).apply {
                text = "Tip: sleep op de kaart de blauwe speld (Object) of rode speld (Fotograaf) naar de juiste plek."
                textSize = 12f
                setTextColor(0xFF5B6873.toInt())
                setPadding(2, dp(8), 2, dp(4))
            })
            root.addView(formPanel)
        }

        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(heading, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { addView(root) }
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        adminScreenView = screen
        setContentView(screen)

        if (currentSection == "home") {
            featuredSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (!updatingFeaturedSelector && position in photos.indices) {
                        pendingFeaturedPhotoId = photos[position].id
                        loadImageInto(photos[position].imageUrl, featuredPreview)
                    }
                }
            }
        } else if (currentSection == "edit") {
            photoSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position != selected) { saveCurrent(); showPhoto(position) }
                }
            }
        }
        if (currentSection != "home") {
            objectTypeSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!updatingFields && photos.isNotEmpty()) {
                        val item = photos[selected]
                        val currentMedia = urlInput.text.toString().trim()
                        if (item.objectType == "MODEL") item.modelUrl = currentMedia else item.imageUrl = currentMedia
                        item.objectType = if (position == 1) "MODEL" else "PHOTO"
                        urlInput.setText(if (item.objectType == "MODEL") item.modelUrl else item.imageUrl)
                        updateTypeFields()
                        persistCurrentForm()
                    }
                }
            }
            testLocationSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!updatingFields && photos.isNotEmpty()) {
                        photos[selected].displayLocation = if (position == 1) "TEST" else "OBJECT"
                        persistCurrentForm()
                    }
                }
            }
        }
    }

    private fun switchSection(section: String) {
        if (section == currentSection) return
        if (currentSection != "home" && ::titleInput.isInitialized) saveCurrent()
        currentSection = section
        buildScreen()
        if (section == "add") {
            addPhoto(skipSave = true)
        } else if (section != "home") {
            showPhoto(selected.coerceIn(0, photos.lastIndex))
        } else {
            pendingFeaturedPhotoId = featuredPhotoId
            refreshFeaturedSelector()
        }
    }

    private fun addSection(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(0xFF174A7E.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    private fun addField(root: LinearLayout, label: String, hint: String): EditText {
        root.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(0xFF53616D.toInt()) })
        val field = EditText(this).apply {
            this.hint = hint
            textSize = 15f
            setSingleLine(true)
            inputType = if (label.contains("URL", ignoreCase = true)) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(field, LinearLayout.LayoutParams(-1, dp(46)))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updatingFields) { hasLocalEditsSinceLaunch = true; persistCurrentForm() }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return field
    }

    private fun coordinateRow(root: LinearLayout, label1: String, hint1: String, label2: String, hint2: String): Pair<EditText, EditText> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply { text = label1; textSize = 12f; setTextColor(0xFF53616D.toInt()) })
        right.addView(TextView(this).apply { text = label2; textSize = 12f; setTextColor(0xFF53616D.toInt()) })
        val first = newCoordinateField(hint1)
        val second = newCoordinateField(hint2)
        left.addView(first, LinearLayout.LayoutParams(-1, dp(46)))
        right.addView(second, LinearLayout.LayoutParams(-1, dp(46)))
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        return Pair(first, second)
    }

    private fun newCoordinateField(hint: String): EditText {
        val field = EditText(this).apply {
            this.hint = hint; textSize = 15f; setSingleLine(true)
            setInputType(android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updatingFields) {
                    hasLocalEditsSinceLaunch = true
                    updateMapFromFields()
                    persistCurrentForm()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return field
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; setOnClickListener { action() }
    }

    private fun showPhoto(index: Int) {
        if (photos.isEmpty()) return
        selected = index.coerceIn(0, photos.lastIndex)
        updatingFields = true
        val p = photos[selected]
        titleInput.setText(p.title); urlInput.setText(if (p.objectType == "MODEL") p.modelUrl else p.imageUrl)
        objectTypeSelector.setSelection(if (p.objectType == "MODEL") 1 else 0, false)
        objectLatInput.setText(p.objectLat.toString()); objectLngInput.setText(p.objectLng.toString())
        testLatInput.setText(if (p.testLat.isFinite()) p.testLat.toString() else "")
        testLngInput.setText(if (p.testLng.isFinite()) p.testLng.toString() else "")
        testLocationSelector.setSelection(if (p.displayLocation == "TEST") 1 else 0, false)
        cameraLatInput.setText(p.cameraLat.toString()); cameraLngInput.setText(p.cameraLng.toString())
        publishCheckbox.isChecked = p.published
        updatingFields = false
        updateTypeFields()
        refreshPhotoSelector()
        if (p.objectType == "PHOTO") loadPreview(p.imageUrl) else preview.setImageDrawable(null)
        updateMapFromFields(fit = true)
    }

    private fun addPhoto(skipSave: Boolean = false) {
        if (!skipSave) saveCurrent()
        // Hergebruik een nog lege invoerfoto in plaats van telkens nieuwe lege records te maken.
        val unfinished = photos.indexOfFirst {
            it.title.equals("Nieuw object", ignoreCase = true) && it.imageUrl.isBlank() && it.modelUrl.isBlank()
        }
        if (unfinished >= 0) {
            showPhoto(unfinished)
            titleInput.requestFocus()
            titleInput.selectAll()
            return
        }
        hasLocalEditsSinceLaunch = true
        photos.add(PhotoConfig("Nieuw object", "", 52.518349, 6.420889, 52.518263, 6.421917, 3f))
        selected = photos.lastIndex
        saveAll()
        showPhoto(selected)
        titleInput.requestFocus()
        titleInput.selectAll()
    }

    private fun removePhoto() {
        if (photos.size <= 1) {
            Toast.makeText(this, "Er moet minimaal één foto blijven bestaan.", Toast.LENGTH_SHORT).show()
            return
        }
        val photoTitle = photos[selected].title.ifBlank { "Deze foto" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Foto definitief verwijderen?")
            .setMessage("Weet je zeker dat je '$photoTitle' wilt verwijderen? Deze actie kan niet ongedaan worden gemaakt.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verwijderen") { _, _ ->
                hasLocalEditsSinceLaunch = true
                val removedPhoto = photos[selected]
                val removedId = removedPhoto.id
                val removedWasPublished = removedPhoto.published
                photos.removeAt(selected)
                if (featuredPhotoId == removedId) {
                    featuredPhotoId = photos.firstOrNull { it.published && it.title.isNotBlank() && it.imageUrl.startsWith("https://") }?.id.orEmpty()
                }
                if (pendingFeaturedPhotoId == removedId) pendingFeaturedPhotoId = featuredPhotoId
                selected = selected.coerceAtMost(photos.lastIndex)
                saveAll()
                refreshPhotoSelector()
                showPhoto(selected)
                if (removedWasPublished) publishCatalog()
            }
            .show()
    }

    private fun saveFeaturedPhoto() {
        val chosen = photos.firstOrNull { it.id == pendingFeaturedPhotoId }
        if (chosen == null) {
            Toast.makeText(this, "Selecteer eerst een foto.", Toast.LENGTH_LONG).show()
            return
        }
        featuredPhotoId = chosen.id
        saveAll()
        // Bewaar de keuze ook als lokale startpagina-override. Zo blijft de nieuwe foto
        // direct zichtbaar in de proefapp, ook wanneer de server-sessie ontbreekt.
        prefs.edit().putString("local_featured_photo_id", chosen.id).apply()
        if (chosen.published && chosen.title.isNotBlank() && chosen.imageUrl.startsWith("https://")) {
            if (sessionToken.isBlank()) {
                Toast.makeText(this,
                    "Startpaginafoto op dit toestel opgeslagen. Log opnieuw in om haar voor iedereen te publiceren.",
                    Toast.LENGTH_LONG).show()
            } else {
                publishCatalog()
            }
        } else {
            Toast.makeText(this, "Keuze op dit toestel opgeslagen. Publiceer deze foto ook om haar voor iedereen zichtbaar te maken.", Toast.LENGTH_LONG).show()
        }
    }

    private fun persistCurrentForm() {
        if (photos.isEmpty() || !::titleInput.isInitialized) return
        val p = photos[selected]
        p.title = titleInput.text.toString().trim()
        val mediaUrl = urlInput.text.toString().trim()
        if (p.objectType == "MODEL") p.modelUrl = mediaUrl else p.imageUrl = mediaUrl
        p.objectLat = objectLatInput.numberOr(p.objectLat)
        p.objectLng = objectLngInput.numberOr(p.objectLng)
        p.cameraLat = cameraLatInput.numberOr(p.cameraLat)
        p.cameraLng = cameraLngInput.numberOr(p.cameraLng)
        p.testLat = if (::testLatInput.isInitialized) testLatInput.text.toString().trim().toDoubleOrNull() ?: Double.NaN else p.testLat
        p.testLng = if (::testLngInput.isInitialized) testLngInput.text.toString().trim().toDoubleOrNull() ?: Double.NaN else p.testLng
        saveAll()
    }

    private fun saveCurrent() {
        persistCurrentForm()
        refreshPhotoSelector()
    }

    private fun refreshPhotoSelector() {
        if (!::photoSelector.isInitialized || photos.isEmpty()) return
        photoSelector.adapter = PhotoThumbnailAdapter()
        photoSelector.setSelection(selected, false)
        refreshFeaturedSelector()
    }

    private fun refreshFeaturedSelector() {
        if (!::featuredSelector.isInitialized || photos.isEmpty()) return
        if (pendingFeaturedPhotoId.isBlank()) pendingFeaturedPhotoId = featuredPhotoId
        if (photos.none { it.id == pendingFeaturedPhotoId }) {
            pendingFeaturedPhotoId = photos.first().id
        }
        updatingFeaturedSelector = true
        featuredSelector.adapter = PhotoThumbnailAdapter()
        val index = photos.indexOfFirst { it.id == pendingFeaturedPhotoId }.coerceAtLeast(0)
        featuredSelector.setSelection(index, false)
        updatingFeaturedSelector = false
        if (::featuredPreview.isInitialized) loadImageInto(photos[index].imageUrl, featuredPreview)
    }

    private inner class PhotoThumbnailAdapter : BaseAdapter() {
        override fun getCount(): Int = photos.size
        override fun getItem(position: Int): PhotoConfig = photos[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = createPhotoSpinnerRow(position)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View = createPhotoSpinnerRow(position, true)
    }

    private fun createPhotoSpinnerRow(position: Int, dropdown: Boolean = false): View {
        val photo = photos[position]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(8), dp(4))
            if (dropdown) setBackgroundColor(0xFFFFFFFF.toInt())
        }
        row.addView(TextView(this).apply {
            text = "${position + 1}. ${photo.title.ifBlank { "Nieuwe foto (nog niet ingevuld)" }}"
            textSize = 14f
            setTextColor(0xFF263746.toInt())
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        val thumbnail = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(44))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFE8EDF2.toInt())
            contentDescription = "Miniatuur: ${photo.title}"
        }
        row.addView(thumbnail)
        loadPhotoThumbnail(photo.imageUrl, thumbnail)
        if (!dropdown) return row
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(row, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@AdminActivity).apply {
                setBackgroundColor(0xFF1769AA.toInt())
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun loadPhotoThumbnail(url: String, target: ImageView) {
        target.tag = url
        if (url.isBlank()) { target.setImageDrawable(null); return }
        val cached = thumbnailCache[url]
        if (cached != null) { target.setImageBitmap(cached); return }
        target.setImageDrawable(null)
        if (!thumbnailLoading.add(url)) return
        Thread {
            var bitmap: Bitmap? = null
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                }
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
                connection.disconnect()
            } catch (_: Exception) { /* A missing thumbnail must not block photo selection. */ }
            runOnUiThread {
                thumbnailLoading.remove(url)
                val loadedBitmap = bitmap
                if (loadedBitmap != null) {
                    thumbnailCache[url] = loadedBitmap
                    if (target.tag == url) target.setImageBitmap(loadedBitmap)
                    if (::photoSelector.isInitialized) (photoSelector.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    if (::featuredSelector.isInitialized) (featuredSelector.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun showFullscreenImage(source: ImageView, title: String) {
        val drawable = source.drawable
        if (drawable == null) {
            Toast.makeText(this, "De foto is nog niet geladen.", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val screen = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        screen.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(drawable.constantState?.newDrawable()?.mutate() ?: drawable)
            contentDescription = title
        }, FrameLayout.LayoutParams(-1, -1))
        screen.addView(Button(this).apply {
            text = "‹"
            textSize = 30f
            contentDescription = "Terug"
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(56), dp(52), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(8); topMargin = dp(8)
        })
        dialog.setContentView(screen)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }

    private fun saveAll() {
        val array = JSONArray()
        photos.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("title", p.title).put("imageUrl", p.imageUrl)
                .put("source", p.source).put("sourceId", p.sourceId)
                .put("objectLat", p.objectLat).put("objectLng", p.objectLng)
                .put("cameraLat", p.cameraLat).put("cameraLng", p.cameraLng)
                .put("imageWidthMeters", p.imageWidthMeters.toDouble())
                .put("imageHeightMeters", p.imageHeightMeters.toDouble())
                .put("type", p.objectType).put("modelUrl", p.modelUrl).put("modelAsset", p.modelAsset)
                .put("testLat", if (p.testLat.isFinite()) p.testLat else JSONObject.NULL)
                .put("testLng", if (p.testLng.isFinite()) p.testLng else JSONObject.NULL)
                .put("displayLocation", p.displayLocation).put("modelScale", p.modelScale.toDouble())
                .put("published", p.published))
        }
        val newJson = array.toString()
        val previousJson = prefs.getString("photos", null)
        val editor = prefs.edit()
        // Bewaar steeds de vorige complete fotolijst als herstelkopie voordat
        // de nieuwe lijst wordt opgeslagen. Een lege/ongeldige lijst overschrijft niets.
        if (!previousJson.isNullOrBlank()) {
            try {
                val previousArray = JSONArray(previousJson)
                if (previousArray.length() > 0) {
                    editor.putString("photos_backup", previousJson)
                }
            } catch (_: Exception) {
                // Een ongeldige vorige waarde wordt niet als herstelkopie gebruikt.
            }
        }
        if (array.length() == 0) {
            Toast.makeText(this, "Opslaan geweigerd: de fotolijst is leeg. Bestaande gegevens zijn behouden.", Toast.LENGTH_LONG).show()
            return
        }
        editor.putString("photos", newJson)
            .putString("featured_photo_id", featuredPhotoId)
            .commit()
    }

    private fun publishCatalog() {
        if (sessionToken.isBlank()) {
            Toast.makeText(this,
                if (localTestMode) "Startpaginafoto is op dit toestel opgeslagen; voor openbare publicatie moet je opnieuw inloggen."
                else "Je sessie is verlopen. Log opnieuw in.",
                Toast.LENGTH_LONG).show()
            return
        }
        saveCurrent()
        val publishablePhotos = photos.filter { item -> item.published && item.title.isNotBlank() &&
            (if (item.objectType == "MODEL") item.modelUrl.startsWith("https://") || item.modelAsset.isNotBlank() else item.imageUrl.startsWith("https://")) }
        if (publishablePhotos.isEmpty()) {
            Toast.makeText(this,
                "Er is geen geldige foto geselecteerd voor openbare publicatie. De bestaande openbare foto's blijven behouden.",
                Toast.LENGTH_LONG).show()
            return
        }
        val publicFeaturedPhotoId = featuredPhotoId.takeIf { id -> publishablePhotos.any { it.id == id } }
            ?: publishablePhotos.firstOrNull()?.id.orEmpty()
        val payload = JSONObject().put("schemaVersion", 2).put("featuredPhotoId", publicFeaturedPhotoId).put("photos", JSONArray().also { array ->
            publishablePhotos.forEach { p ->
                array.put(JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("imageUrl", p.imageUrl)
                    .put("source", p.source)
                    .put("sourceId", p.sourceId)
                    .put("objectLat", p.objectLat)
                    .put("objectLng", p.objectLng)
                    .put("cameraLat", p.cameraLat)
                    .put("cameraLng", p.cameraLng)
                    .put("imageWidthMeters", p.imageWidthMeters.toDouble())
                    .put("imageHeightMeters", p.imageHeightMeters.toDouble())
                    .put("type", p.objectType).put("modelUrl", p.modelUrl).put("modelAsset", p.modelAsset)
                    .put("testLat", if (p.testLat.isFinite()) p.testLat else JSONObject.NULL)
                    .put("testLng", if (p.testLng.isFinite()) p.testLng else JSONObject.NULL)
                    .put("displayLocation", p.displayLocation).put("modelScale", p.modelScale.toDouble()))
            }
        })
        Toast.makeText(this, "Catalogus wordt gepubliceerd…", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$WORKER_BASE_URL/photos").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $sessionToken")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                val result = JSONObject(response)
                runOnUiThread {
                    if (status in 200..299 && result.optBoolean("success")) {
                        val publicCache = JSONObject(payload.toString()).put("featuredPhotoId", publicFeaturedPhotoId)
                        prefs.edit()
                            .putString("public_catalog_json", publicCache.toString())
                            .putLong("public_catalog_fetched_at", System.currentTimeMillis())
                            .remove("local_featured_photo_id")
                            .apply()
                        Toast.makeText(this, "Catalogus is openbaar bijgewerkt.", Toast.LENGTH_LONG).show()
                    } else if (status == 401) {
                        prefs.edit().remove("admin_session_token").remove("admin_session_expires_at").remove("admin_session_last_refresh_at").apply()
                        val message = if (localTestMode) {
                            "De startpaginafoto is op dit toestel opgeslagen, maar niet openbaar gepubliceerd. Log opnieuw in om te publiceren."
                        } else {
                            "Je sessie is verlopen. Log opnieuw in."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, result.optString("error").ifBlank { "Publiceren mislukt (HTTP $status)." }, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Publiceren mislukt: ${e.message ?: "netwerkfout"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun logout() {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$WORKER_BASE_URL/auth/logout").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer $sessionToken")
                }
                connection.responseCode
            } catch (_: Exception) {
                // The local session is cleared even if the network is unavailable.
            } finally {
                connection?.disconnect()
                prefs.edit().remove("admin_session_token").remove("admin_session_expires_at").apply()
                runOnUiThread { finish() }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK && data != null) {
            val id = data.getStringExtra("photoId").orEmpty()
            val width = data.getFloatExtra("imageWidthMeters", -1f)
            val height = data.getFloatExtra("imageHeightMeters", -1f)
            val photo = photos.firstOrNull { it.id == id }
            if (photo != null) {
                if (width in 1f..12f) photo.imageWidthMeters = width
                if (height in 1f..3f) photo.imageHeightMeters = height
                saveAll()
                if (photo.published) publishCatalog()
            }
        }
    }

    private fun openCamera() {
        val p = photos[selected]
        if (p.objectType == "MODEL") {
            if (p.modelUrl.isBlank() && p.modelAsset.isBlank()) { Toast.makeText(this, "Vul eerst de URL van het GLB-model in.", Toast.LENGTH_LONG).show(); return }
            if (p.displayLocation == "TEST" && (!p.testLat.isFinite() || !p.testLng.isFinite())) {
                Toast.makeText(this, "Vul eerst de testlocatie van het object in.", Toast.LENGTH_LONG).show(); return
            }
            val useTest = p.displayLocation == "TEST"
            startActivityForResult(Intent(this, ModelArActivity::class.java).apply {
                putExtra("photoTitle", p.title)
                putExtra("modelUrl", p.modelUrl)
                putExtra("modelAsset", p.modelAsset)
                putExtra("objectLat", p.objectLat); putExtra("objectLng", p.objectLng)
                putExtra("targetLat", if (useTest) p.testLat else p.objectLat)
                putExtra("targetLng", if (useTest) p.testLng else p.objectLng)
            }, 3001)
        } else {
            if (p.imageUrl.isBlank()) { Toast.makeText(this, "Vul eerst de URL van de foto in.", Toast.LENGTH_LONG).show(); return }
            startActivityForResult(Intent(this, MainActivity::class.java).apply {
                putExtra("title", p.title); putExtra("imageUrl", p.imageUrl)
                putExtra("objectLat", p.objectLat); putExtra("objectLng", p.objectLng)
                putExtra("cameraLat", p.cameraLat); putExtra("cameraLng", p.cameraLng)
                putExtra("imageWidthMeters", p.imageWidthMeters)
                putExtra("imageHeightMeters", p.imageHeightMeters)
                putExtra("photoIndex", selected)
                putExtra("photoId", p.id)
                putExtra("admin_camera", true)
            }, 3001)
        }
        returningFromCamera = true
    }

    private fun addPreviewFromUrl() {
        val url = urlInput.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Vul eerst de URL van de foto in.", Toast.LENGTH_LONG).show()
            return
        }
        persistCurrentForm()
        loadPreview(url, showMessage = true)
    }

    private fun loadPreview(url: String, showMessage: Boolean = false) {
        if (photos.getOrNull(selected)?.objectType == "MODEL") { preview.setImageDrawable(null); return }
        loadImageInto(url, preview, showMessage)
    }

    private fun updateTypeFields() {
        if (!::objectTypeSelector.isInitialized || photos.isEmpty()) return
        val isModel = photos[selected].objectType == "MODEL"
        cameraFieldsContainer.visibility = if (isModel) View.GONE else View.VISIBLE
        testFieldsContainer.visibility = if (isModel) View.VISIBLE else View.GONE
        preview.visibility = if (isModel) View.GONE else View.VISIBLE
        urlInput.hint = if (isModel) "HTTPS-URL naar een GLB 2.0-bestand" else "HTTPS-URL van de historische foto"
        modelPreviewButton?.visibility = if (isModel) View.VISIBLE else View.GONE
        if (isModel) preview.setImageDrawable(null)
    }

    private fun loadImageInto(url: String, target: ImageView, showMessage: Boolean = false) {
        target.tag = url
        target.setImageDrawable(null)
        if (url.isBlank()) return
        Thread {
            try {
                val bitmap = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Geen afbeelding gevonden")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && target.tag == url) {
                        target.setImageBitmap(bitmap)
                        if (showMessage) Toast.makeText(this, "Foto geladen en gegevens opgeslagen.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        target.setImageDrawable(null)
                        if (showMessage) Toast.makeText(this, "Foto laden mislukt. Controleer de URL.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
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
                Toast.makeText(this, "Achtergrondkaart opgeslagen", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun openMapPage() {
        if (mapScreenActive) return
        if (adminScreenView == null) return
        mapScreenActive = true
        val screen = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(MapBridge(), "TijdBeeld")
        }
        screen.addView(map, FrameLayout.LayoutParams(-1, -1))

        val mapHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setBackgroundColor(0xFF1769AA.toInt())
        }
        mapHeader.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            contentDescription = "Terug naar beheer"
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(16), 0)
            setOnClickListener { closeMapPage() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        mapHeader.addView(TextView(this).apply {
            text = "AR-TijdBeeld"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(mapHeader, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(0xEFFFFFFF.toInt())
        }
        zoomControls.addView(actionButton("+") {
            if (mapReady) map.evaluateJavascript("map.zoomIn();", null)
        }, LinearLayout.LayoutParams(dp(52), dp(44)))
        zoomControls.addView(actionButton("−") {
            if (mapReady) map.evaluateJavascript("map.zoomOut();", null)
        }, LinearLayout.LayoutParams(dp(52), dp(44)))
        val mapZoomLabel = TextView(this).apply {
            text = "Zoomniveau: —"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF174A7E.toInt())
            setBackgroundColor(0xEFFFFFFF.toInt())
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }
        zoomControls.addView(mapZoomLabel, LinearLayout.LayoutParams(dp(88), -2))
        screen.addView(zoomControls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(8); topMargin = dp(68)
        })

        screen.addView(actionButton("Opslaan") {
            saveCurrent()
            if (mapReady) map.evaluateJavascript("TijdBeeld.saveMapView();", null)
            Toast.makeText(this@AdminActivity, "Kaartposities opgeslagen", Toast.LENGTH_SHORT).show()
        }, FrameLayout.LayoutParams(dp(170), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10)
        })

        setContentView(screen)
        map.loadDataWithBaseURL("https://localhost/", mapHtml(), "text/html", "UTF-8", null)
    }

    private fun closeMapPage() {
        if (!mapScreenActive) return
        val oldMap = map
        mapReady = false
        mapScreenActive = false
        adminScreenView?.let { setContentView(it) }
        oldMap.stopLoading()
        oldMap.onPause()
        oldMap.removeAllViews()
        oldMap.destroy()
    }

    override fun onBackPressed() {
        if (mapScreenActive) closeMapPage() else super.onBackPressed()
    }

    private fun updateMapFromFields(fit: Boolean = false) {
        val olat = objectLatInput.numberOr(52.518349)
        val olng = objectLngInput.numberOr(6.420889)
        val clat = cameraLatInput.numberOr(52.518263)
        val clng = cameraLngInput.numberOr(6.421917)
        val js = "if(window.setPoints){setPoints($olat,$olng,$clat,$clng,$fit);}"
        if (mapReady && mapScreenActive) map.evaluateJavascript(js, null)
    }

    private fun mapHtml(): String {
        val olat = objectLatInput.numberOr(52.518349)
        val olng = objectLngInput.numberOr(6.420889)
        val clat = cameraLatInput.numberOr(52.518263)
        val clng = cameraLngInput.numberOr(6.421917)
        val centerLat = prefs.getString("mapCenterLat", null)?.toDoubleOrNull() ?: (olat + clat) / 2.0
        val centerLng = prefs.getString("mapCenterLng", null)?.toDoubleOrNull() ?: (olng + clng) / 2.0
        val zoom = prefs.getInt("mapZoom", 17)
        val mapBackground = prefs.getString("map_background", "pdok")
        val tileLayerJs = when (mapBackground) {
            "osm" -> "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);"
            "opentopo" -> "L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',{maxZoom:18,maxNativeZoom:17,attribution:'© OpenTopoMap (CC-BY-SA)'}).addTo(map);"
            else -> "L.tileLayer('https://service.pdok.nl/brt/achtergrondkaart/wmts/v2_0/standaard/EPSG:3857/{z}/{x}/{y}.png',{minZoom:6,maxZoom:19,attribution:'© Kadaster / PDOK'}).addTo(map);"
        }
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>
          html,body,#map{height:100%;width:100%;margin:0}
          .pin-label{display:flex;align-items:center;gap:4px;white-space:nowrap;font:bold 13px sans-serif;color:#17212b;text-shadow:0 1px 2px white,1px 0 2px white,-1px 0 2px white,0 -1px 2px white}
          .map-pin{width:27px;height:36px;display:block;filter:drop-shadow(0 1px 2px #555)}
          .leaflet-div-icon{background:transparent;border:0}
        </style>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script></head>
        <body><div id="map"></div><script>
        var map=L.map('map',{dragging:true,touchZoom:true,scrollWheelZoom:true,doubleClickZoom:true,zoomControl:false}).setView([${centerLat},${centerLng}],${zoom});
        $tileLayerJs
        function labeledIcon(label,color){
          var svg='<svg class="map-pin" viewBox="0 0 32 42" xmlns="http://www.w3.org/2000/svg"><path d="M16 1C7.7 1 1 7.7 1 16c0 11 15 25 15 25s15-14 15-25C31 7.7 24.3 1 16 1z" fill="'+color+'" stroke="white" stroke-width="1.5"/><circle cx="16" cy="16" r="5.2" fill="white"/></svg>';
          return L.divIcon({className:'',iconSize:[112,40],iconAnchor:[15,36],
            html:'<div class="pin-label">'+svg+'<span>'+label+'</span></div>'});
        }
        var objectPin=L.marker([${olat},${olng}],{draggable:true,icon:labeledIcon('Object','#1769d2')}).addTo(map);
        var cameraPin=L.marker([${clat},${clng}],{draggable:true,icon:labeledIcon('Fotograaf','#d52b35')}).addTo(map);
        objectPin.on('dragend',function(){var p=objectPin.getLatLng();TijdBeeld.onMapMove('object',p.lat,p.lng);});
        cameraPin.on('dragend',function(){var p=cameraPin.getLatLng();TijdBeeld.onMapMove('camera',p.lat,p.lng);});
        function setPoints(a,b,c,d,fit){
          objectPin.setLatLng([a,b]);cameraPin.setLatLng([c,d]);
          if(fit!==false){
            var center=L.latLng((a+c)/2,(b+d)/2);
            map.setView(center,17,{animate:false});
            map.whenReady(function(){
              var p1=map.latLngToContainerPoint([a,b]);
              var p2=map.latLngToContainerPoint([c,d]);
              var actual=Math.max(1,p1.distanceTo(p2));
              var desired=Math.max(24,map.getSize().x*0.10);
              var adjusted=Math.max(5,Math.min(19,map.getZoom()+Math.log2(desired/actual)));
              map.setView(center,adjusted,{animate:false});
            });
          }
        }
        function saveMapView(){var p=map.getCenter();TijdBeeld.onMapViewport(p.lat,p.lng,map.getZoom());}
        map.on('moveend',saveMapView);
        map.on('zoomend',function(){saveMapView();TijdBeeld.onMapZoom(map.getZoom());});
        window.addEventListener('load',function(){setTimeout(function(){map.invalidateSize();TijdBeeld.onMapReady();TijdBeeld.onMapZoom(map.getZoom());},350);});
        </script></body></html>
        """.trimIndent()
    }

    inner class MapBridge {
        @JavascriptInterface fun onMapReady() {
            runOnUiThread {
                mapReady = true
                val olat = objectLatInput.numberOr(52.518349)
                val olng = objectLngInput.numberOr(6.420889)
                val clat = cameraLatInput.numberOr(52.518263)
                val clng = cameraLngInput.numberOr(6.421917)
                val hasSavedView = prefs.getString("mapCenterLat", null)?.toDoubleOrNull() != null &&
                    prefs.getString("mapCenterLng", null)?.toDoubleOrNull() != null
                map.evaluateJavascript("setPoints($olat,$olng,$clat,$clng,${!hasSavedView});", null)
            }
        }
        @JavascriptInterface fun onMapMove(kind: String, lat: Double, lng: Double) {
            runOnUiThread {
                updatingFields = true
                if (kind == "object") { objectLatInput.setText(lat.toString()); objectLngInput.setText(lng.toString()) }
                else { cameraLatInput.setText(lat.toString()); cameraLngInput.setText(lng.toString()) }
                updatingFields = false
                hasLocalEditsSinceLaunch = true
            }
        }
        @JavascriptInterface fun onMapViewport(lat: Double, lng: Double, zoom: Int) {
            prefs.edit().putString("mapCenterLat", lat.toString())
                .putString("mapCenterLng", lng.toString())
                .putInt("mapZoom", zoom).apply()
        }
        @JavascriptInterface fun onMapZoom(zoom: Int) {
            runOnUiThread {
                val screen = window.decorView
                fun findLabel(view: View): TextView? {
                    if (view is TextView && view.text.toString().startsWith("Zoomniveau:")) return view
                    if (view is ViewGroup) {
                        for (i in 0 until view.childCount) findLabel(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                findLabel(screen)?.text = "Zoomniveau: $zoom"
            }
        }
    }

    private fun EditText.numberOr(fallback: Double): Double =
        text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val WORKER_BASE_URL = "https://tijdbeeld.leeuw008.workers.dev"
    }
}
