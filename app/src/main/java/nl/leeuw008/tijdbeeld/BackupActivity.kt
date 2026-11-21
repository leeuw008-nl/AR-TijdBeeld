package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports/restores user-owned TijdBeeld preferences through Android's document picker.
 * Authentication tokens and account credentials are deliberately never exported.
 */
class BackupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE) }

    private val backupKeys = linkedSetOf(
        "photos",
        "photos_backup",
        "featured_photo_id",
        "local_featured_photo_id",
        "public_catalog_json",
        "public_catalog_fetched_at",
        "map_background",
        "mapCenterLat",
        "mapCenterLng",
        "mapZoom"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(0xFFF3F6F9.toInt())
        }
        root.addView(TextView(this).apply {
            text = "TijdBeeld – back-up en herstellen"
            textSize = 22f
            setTextColor(0xFF174A7E.toInt())
            setPadding(0, 0, 0, dp(14))
        })
        root.addView(TextView(this).apply {
            text = "Bewaar je fotogegevens, locaties, fotobreedtes, startpaginakeuze, kaartinstellingen en lokale catalogus in één JSON-bestand. Beheerderswachtwoorden en sessietokens worden uit veiligheid niet meegenomen."
            textSize = 15f
            setTextColor(0xFF334155.toInt())
            setPadding(0, 0, 0, dp(20))
        })
        root.addView(Button(this).apply {
            text = "Back-up exporteren"
            textSize = 16f
            setOnClickListener { createBackupFile() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        root.addView(Button(this).apply {
            text = "Back-up terugzetten"
            textSize = 16f
            setOnClickListener { chooseBackupFile() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        root.addView(Button(this).apply {
            text = "Terug"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun createBackupFile() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "TijdBeeld-backup-$stamp.json")
        }
        startActivityForResult(intent, REQUEST_CREATE)
    }

    private fun chooseBackupFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    @Deprecated("Uses the Android document picker for compatibility with Android 12")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_CREATE -> exportTo(uri)
            REQUEST_OPEN -> readBackup(uri)
        }
    }

    private fun exportTo(uri: Uri) {
        try {
            val root = JSONObject()
                .put("format", FORMAT_ID)
                .put("schemaVersion", SCHEMA_VERSION)
                .put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                .put("sourcePackage", packageName)
            val values = JSONObject()
            val all = prefs.all
            for (key in backupKeys) {
                if (!all.containsKey(key)) continue
                val value = all[key] ?: continue
                val item = JSONObject()
                when (value) {
                    is String -> item.put("type", "string").put("value", value)
                    is Boolean -> item.put("type", "boolean").put("value", value)
                    is Int -> item.put("type", "int").put("value", value)
                    is Long -> item.put("type", "long").put("value", value)
                    is Float -> item.put("type", "float").put("value", value.toDouble())
                    else -> continue
                }
                values.put(key, item)
            }
            if (!values.has("photos") && !values.has("photos_backup")) {
                Toast.makeText(this, "Er zijn geen opgeslagen fotogegevens gevonden. Er is geen back-upbestand gemaakt.", Toast.LENGTH_LONG).show()
                return
            }
            root.put("preferences", values)
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(root.toString(2).toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: throw IllegalStateException("Het gekozen bestand kan niet worden geschreven.")
            Toast.makeText(this, "Back-up geëxporteerd. Bewaar dit JSON-bestand in je cloud.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Exporteren mislukt: ${e.message ?: "onbekende fout"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBackup(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Het bestand kan niet worden gelezen.")
            val root = JSONObject(raw)
            require(root.optString("format") == FORMAT_ID) { "Dit is geen TijdBeeld-back-up." }
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Deze back-upversie wordt niet ondersteund." }
            val values = root.optJSONObject("preferences") ?: throw IllegalArgumentException("De back-up bevat geen gegevens.")
            require(values.has("photos") || values.has("photos_backup")) { "De back-up bevat geen fotogegevens." }

            val restored = linkedMapOf<String, Any>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key in backupKeys) { "Onbekende back-upinstelling: $key" }
                val item = values.getJSONObject(key)
                val value = item.get("value")
                val decoded: Any = when (item.optString("type")) {
                    "string" -> value as? String ?: throw IllegalArgumentException("Ongeldig tekstveld: $key")
                    "boolean" -> value as? Boolean ?: throw IllegalArgumentException("Ongeldig ja/nee-veld: $key")
                    "int" -> (value as? Number)?.toInt() ?: throw IllegalArgumentException("Ongeldig getal: $key")
                    "long" -> (value as? Number)?.toLong() ?: throw IllegalArgumentException("Ongeldig getal: $key")
                    "float" -> (value as? Number)?.toFloat() ?: throw IllegalArgumentException("Ongeldig getal: $key")
                    else -> throw IllegalArgumentException("Onbekend gegevenstype: $key")
                }
                if (key == "photos" || key == "photos_backup") {
                    val array = JSONArray(decoded as String)
                    require(array.length() > 0) { "De fotolijst in de back-up is leeg." }
                    for (i in 0 until array.length()) {
                        require(array.optJSONObject(i) != null) { "De fotolijst bevat ongeldige gegevens." }
                    }
                }
                restored[key] = decoded
            }

            AlertDialog.Builder(this)
                .setTitle("Back-up terugzetten?")
                .setMessage("De lokale TijdBeeld-fotogegevens en instellingen op dit toestel worden vervangen door de gegevens uit deze back-up. Je beheerderssessie en inloggegevens blijven behouden. Er wordt niets gewijzigd voordat je bevestigt.")
                .setNegativeButton("Annuleren", null)
                .setPositiveButton("Terugzetten") { _, _ -> applyRestore(restored) }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Back-up niet teruggezet: ${e.message ?: "bestand ongeldig"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyRestore(values: Map<String, Any>) {
        try {
            val editor = prefs.edit()
            backupKeys.forEach { editor.remove(it) }
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            if (!editor.commit()) throw IllegalStateException("Android kon de gegevens niet opslaan.")
            Toast.makeText(this, "Back-up teruggezet. Sluit TijdBeeld en open de app opnieuw.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Herstellen mislukt: ${e.message ?: "onbekende fout"}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_CREATE = 4101
        private const val REQUEST_OPEN = 4102
        private const val FORMAT_ID = "nl.leeuw008.tijdbeeld.backup"
        private const val SCHEMA_VERSION = 1
    }
}
