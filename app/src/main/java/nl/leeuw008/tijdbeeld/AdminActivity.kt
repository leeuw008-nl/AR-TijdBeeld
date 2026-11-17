package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
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
    var sourceId: String = ""
) {
    fun caption() = title
}

class AdminActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE) }
    private val photos = mutableListOf<PhotoConfig>()
    private var selected = 0
    private lateinit var titleInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var objectLatInput: EditText
    private lateinit var objectLngInput: EditText
    private lateinit var cameraLatInput: EditText
    private lateinit var cameraLngInput: EditText
    private lateinit var map: WebView
    private var mapDialog: Dialog? = null
    private lateinit var preview: ImageView
    private lateinit var photoSelector: Spinner
    private var sessionToken: String = ""
    private var updatingFields = false
    private var mapReady = false
    private var returningFromCamera = false
    private var hasLocalEditsSinceLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("admin_authenticated", false).not()) {
            finish()
            return
        }
        sessionToken = prefs.getString("admin_session_token", "").orEmpty()
        val expiresAt = prefs.getLong("admin_session_expires_at", 0L)
        if (sessionToken.isBlank() || System.currentTimeMillis() >= expiresAt) {
            prefs.edit().remove("admin_session_token").remove("admin_session_expires_at").apply()
            finish()
            return
        }
        loadPhotos()
        buildScreen()
        showPhoto(0)
        loadPublicCatalog()
    }

    override fun onResume() {
        super.onResume()
        if (returningFromCamera) {
            returningFromCamera = false
            hasLocalEditsSinceLaunch = true
            loadPhotos()
            showPhoto(selected.coerceIn(0, photos.lastIndex))
        }
    }

    private fun loadPhotos() {
        photos.clear()
        val raw = prefs.getString("photos", null)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    photos.add(PhotoConfig(
                        o.optString("title"), o.optString("imageUrl"),
                        o.optDouble("objectLat", 52.518349), o.optDouble("objectLng", 6.420889),
                        o.optDouble("cameraLat", 52.518263), o.optDouble("cameraLng", 6.421917),
                        o.optDouble("imageWidthMeters", 3.0).toFloat().coerceIn(1f, 12f),
                        o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        o.optString("source"), o.optString("sourceId")
                    ))
                }
            } catch (_: Exception) { photos.clear() }
        }
        if (photos.isEmpty()) photos.add(PhotoConfig(
            "1910 - Kantongerechtsgebouw",
            "https://oudommen-production.s3.eu-central-1.amazonaws.com/renditions/IO0042d1/large.jpg",
            52.518349, 6.420889, 52.518263, 6.421917, 3f
        ))
        saveAll()
    }

    private fun loadPublicCatalog() {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(WORKER_BASE_URL + "/photos").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Catalogus HTTP ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(body)
                val array = root.getJSONArray("photos")
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
                        o.optString("source"), o.optString("sourceId")
                    ))
                }
                if (remotePhotos.isEmpty()) throw IllegalStateException("De openbare catalogus bevat geen foto's")
                runOnUiThread {
                    if (!hasLocalEditsSinceLaunch) {
                        val localOnly = photos.filter { local ->
                            remotePhotos.none { remote ->
                                remote.id == local.id ||
                                    (local.imageUrl.isNotBlank() && remote.imageUrl == local.imageUrl)
                            }
                        }
                        photos.clear()
                        photos.addAll(remotePhotos)
                        photos.addAll(localOnly)
                        selected = selected.coerceIn(0, photos.lastIndex)
                        saveAll()
                        showPhoto(selected)
                    }
                }
            } catch (_: Exception) {
                // Offline: continue using the locally cached catalog.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }
        val heading = TextView(this).apply {
            text = "TijdBeeld • Beheer historische foto's"
            textSize = 21f
            setTextColor(0xFF174A7E.toInt())
            setPadding(0, 4, 0, 12)
        }
        root.addView(heading)
        photoSelector = Spinner(this)
        root.addView(photoSelector)
        val selectorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectorRow.addView(actionButton("Nieuwe foto") { addPhoto() }, LinearLayout.LayoutParams(0, -2, 1f))
        selectorRow.addView(actionButton("Verwijder") { removePhoto() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(selectorRow)

        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(0xFFE8EDF2.toInt())
            minimumHeight = dp(90)
            maxHeight = dp(190)
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(8); bottomMargin = dp(8) })

        titleInput = addField(root, "Titel / bijschrift", "Bijv. 1910 - Kantongerechtsgebouw").apply {
            setSelectAllOnFocus(true)
        }
        urlInput = addField(root, "URL van de historische foto", "https://…")
        root.addView(actionButton("Toevoegen") { addPreviewFromUrl() },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(2); bottomMargin = dp(6) })
        addSection(root, "1. Locatie van het historische object")
        val objectCoordinates = coordinateRow(root, "Breedtegraad (latitude)", "52.518349", "Lengtegraad (longitude)", "6.420889")
        objectLatInput = objectCoordinates.first
        objectLngInput = objectCoordinates.second
        addSection(root, "2. Locatie waar de foto is gemaakt")
        val cameraCoordinates = coordinateRow(root, "Breedtegraad (latitude)", "52.518263", "Lengtegraad (longitude)", "6.421917")
        cameraLatInput = cameraCoordinates.first
        cameraLngInput = cameraCoordinates.second

        root.addView(actionButton("Open topografische kaart (apart venster)") { openMapWindow() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12); bottomMargin = dp(8) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("Opslaan") { saveCurrent(); loadPreview(urlInput.text.toString().trim()); Toast.makeText(this@AdminActivity, "Foto en posities opgeslagen", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(actionButton("Bekijk via camera") { saveCurrent(); openCamera() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)

        addSection(root, "3. Gedeelde catalogus publiceren")
        root.addView(TextView(this).apply {
            text = "Je bent ingelogd als beheerder. Publiceren is beveiligd met je tijdelijke sessie."
            textSize = 13f
            setTextColor(0xFF53616D.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(actionButton("Publiceer foto's en instellingen voor iedereen") { publishCatalog() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6); bottomMargin = dp(6) })
        root.addView(actionButton("Uitloggen") { logout() },
            LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
        val note = TextView(this).apply {
            text = "Tip: voer de coördinaten naast elkaar in. Open de kaart in het aparte venster en sleep de blauwe speld (Object) of rode speld (Fotograaf) naar de juiste plek. Verplaats het kaartvenster via de bovenbalk."
            textSize = 12f
            setTextColor(0xFF5B6873.toInt())
            setPadding(2, dp(8), 2, dp(4))
        }
        root.addView(note)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        photoSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != selected) { saveCurrent(); showPhoto(position) }
            }
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
        titleInput.setText(p.title); urlInput.setText(p.imageUrl)
        objectLatInput.setText(p.objectLat.toString()); objectLngInput.setText(p.objectLng.toString())
        cameraLatInput.setText(p.cameraLat.toString()); cameraLngInput.setText(p.cameraLng.toString())
        updatingFields = false
        refreshPhotoSelector()
        loadPreview(p.imageUrl)
        updateMapFromFields(fit = true)
    }

    private fun addPhoto() {
        saveCurrent()
        hasLocalEditsSinceLaunch = true
        photos.add(PhotoConfig("Nieuwe historische foto", "", 52.518349, 6.420889, 52.518263, 6.421917, 3f))
        selected = photos.lastIndex
        saveAll()
        showPhoto(selected)
        titleInput.requestFocus()
        titleInput.selectAll()
    }

    private fun removePhoto() {
        if (photos.size <= 1) { Toast.makeText(this, "Er moet minimaal één foto blijven bestaan.", Toast.LENGTH_SHORT).show(); return }
        hasLocalEditsSinceLaunch = true
        photos.removeAt(selected)
        selected = selected.coerceAtMost(photos.lastIndex)
        saveAll(); showPhoto(selected)
    }

    private fun persistCurrentForm() {
        if (photos.isEmpty() || !::titleInput.isInitialized) return
        val p = photos[selected]
        p.title = titleInput.text.toString().trim()
        p.imageUrl = urlInput.text.toString().trim()
        p.objectLat = objectLatInput.numberOr(p.objectLat)
        p.objectLng = objectLngInput.numberOr(p.objectLng)
        p.cameraLat = cameraLatInput.numberOr(p.cameraLat)
        p.cameraLng = cameraLngInput.numberOr(p.cameraLng)
        saveAll()
    }

    private fun saveCurrent() {
        persistCurrentForm()
        refreshPhotoSelector()
    }

    private fun refreshPhotoSelector() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            photos.mapIndexed { i, item -> "${i + 1}. ${item.title.ifBlank { "Nieuwe foto" }}" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        photoSelector.adapter = adapter
        photoSelector.setSelection(selected, false)
    }

    private fun saveAll() {
        val array = JSONArray()
        photos.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("title", p.title).put("imageUrl", p.imageUrl)
                .put("source", p.source).put("sourceId", p.sourceId)
                .put("objectLat", p.objectLat).put("objectLng", p.objectLng)
                .put("cameraLat", p.cameraLat).put("cameraLng", p.cameraLng)
                .put("imageWidthMeters", p.imageWidthMeters.toDouble()))
        }
        prefs.edit().putString("photos", array.toString()).commit()
    }

    private fun publishCatalog() {
        if (sessionToken.isBlank()) {
            Toast.makeText(this, "Je sessie is verlopen. Log opnieuw in.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        saveCurrent()
        val publishablePhotos = photos.filter { it.title.isNotBlank() && it.imageUrl.startsWith("https://") }
        if (publishablePhotos.isEmpty()) {
            Toast.makeText(this, "Voeg eerst minimaal één foto met titel en HTTPS-URL toe.", Toast.LENGTH_LONG).show()
            return
        }
        val payload = JSONObject().put("schemaVersion", 1).put("photos", JSONArray().also { array ->
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
                    .put("imageWidthMeters", p.imageWidthMeters.toDouble()))
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
                        Toast.makeText(this, "Catalogus is openbaar bijgewerkt.", Toast.LENGTH_LONG).show()
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

    private fun openCamera() {
        val p = photos[selected]
        if (p.title.trim() == OMMERSCHANS_PHOTO_TITLE) {
            startActivity(Intent(this, ModelArActivity::class.java))
            returningFromCamera = true
            return
        }
        if (p.imageUrl.isBlank()) { Toast.makeText(this, "Vul eerst de URL van de foto in.", Toast.LENGTH_LONG).show(); return }
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("title", p.title); putExtra("imageUrl", p.imageUrl)
            putExtra("objectLat", p.objectLat); putExtra("objectLng", p.objectLng)
            putExtra("cameraLat", p.cameraLat); putExtra("cameraLng", p.cameraLng)
            putExtra("imageWidthMeters", p.imageWidthMeters)
            putExtra("photoIndex", selected)
            putExtra("photoId", p.id)
        })
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
        preview.setImageDrawable(null)
        if (url.isBlank()) return
        Thread {
            try {
                val bitmap = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Geen afbeelding gevonden")
                runOnUiThread {
                    preview.setImageBitmap(bitmap)
                    if (showMessage) Toast.makeText(this, "Foto geladen en gegevens opgeslagen.", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    preview.setImageDrawable(null)
                    if (showMessage) Toast.makeText(this, "Foto laden mislukt. Controleer de URL.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openMapWindow() {
        if (mapDialog?.isShowing == true) return
        val dialog = Dialog(this)
        mapDialog = dialog
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFFFFFFF.toInt()) }
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF174A7E.toInt())
        }
        titleBar.addView(TextView(this).apply {
            text = "Kaartposities aanpassen • sleep deze balk"
            textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleBar.addView(Button(this).apply {
            text = "Sluiten"; textSize = 12f
            setOnClickListener { dialog.dismiss() }
        })
        var downX = 0f
        var downY = 0f
        titleBar.setOnTouchListener { _, event ->
            val window = dialog.window
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    if (window != null) {
                        val params = window.attributes
                        params.x += (event.rawX - downX).toInt()
                        params.y += (event.rawY - downY).toInt()
                        window.attributes = params
                    }
                    downX = event.rawX; downY = event.rawY; true
                }
                else -> true
            }
        }
        container.addView(titleBar, LinearLayout.LayoutParams(-1, dp(48)))
        map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(MapBridge(), "TijdBeeld")
        }
        container.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        val mapActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xFFF1F4F7.toInt())
        }
        mapActions.addView(actionButton("Opslaan") {
            saveCurrent()
            if (mapReady) map.evaluateJavascript("TijdBeeld.saveMapView();", null)
            Toast.makeText(this@AdminActivity, "Kaartposities opgeslagen", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        mapActions.addView(actionButton("Sluiten") { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        container.addView(mapActions, LinearLayout.LayoutParams(-1, dp(56)))
        dialog.setContentView(container)
        dialog.setOnDismissListener { mapReady = false; mapDialog = null }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.white)
            setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.78).toInt())
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        map.loadDataWithBaseURL("https://localhost/", mapHtml(), "text/html", "UTF-8", null)
    }

    private fun updateMapFromFields(fit: Boolean = false) {
        val olat = objectLatInput.numberOr(52.518349)
        val olng = objectLngInput.numberOr(6.420889)
        val clat = cameraLatInput.numberOr(52.518263)
        val clng = cameraLngInput.numberOr(6.421917)
        val js = "if(window.setPoints){setPoints($olat,$olng,$clat,$clng,$fit);}"
        if (mapReady && mapDialog?.isShowing == true) map.evaluateJavascript(js, null)
    }

    private fun mapHtml(): String {
        val olat = objectLatInput.numberOr(52.518349)
        val olng = objectLngInput.numberOr(6.420889)
        val clat = cameraLatInput.numberOr(52.518263)
        val clng = cameraLngInput.numberOr(6.421917)
        val centerLat = prefs.getString("mapCenterLat", null)?.toDoubleOrNull() ?: (olat + clat) / 2.0
        val centerLng = prefs.getString("mapCenterLng", null)?.toDoubleOrNull() ?: (olng + clng) / 2.0
        val zoom = prefs.getInt("mapZoom", 17)
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
        var map=L.map('map',{dragging:true,touchZoom:true,scrollWheelZoom:true,doubleClickZoom:true}).setView([${centerLat},${centerLng}],${zoom});
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:20,attribution:'© OpenStreetMap'}).addTo(map);
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
          if(fit!==false){var bounds=L.latLngBounds([[a,b],[c,d]]);map.fitBounds(bounds.pad(0.35),{maxZoom:19});}
        }
        function saveMapView(){var p=map.getCenter();TijdBeeld.onMapViewport(p.lat,p.lng,map.getZoom());}
        map.on('moveend',saveMapView);
        window.addEventListener('load',function(){setTimeout(function(){map.invalidateSize();TijdBeeld.onMapReady();},350);});
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
            }
        }
        @JavascriptInterface fun onMapViewport(lat: Double, lng: Double, zoom: Int) {
            prefs.edit().putString("mapCenterLat", lat.toString())
                .putString("mapCenterLng", lng.toString())
                .putInt("mapZoom", zoom).apply()
        }
    }

    private fun EditText.numberOr(fallback: Double): Double =
        text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val WORKER_BASE_URL = "https://tijdbeeld.leeuw008.workers.dev"
    }
}
