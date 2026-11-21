package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the app's locally stored user data from the #139 baseline.
 * Admin session tokens are intentionally excluded; they must be renewed by logging in.
 */
class BackupActivity : Activity() {
    private val requestCreateBackup = 7301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF3F6F9.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Back-up maken"
            textSize = 24f
            setTextColor(0xFF1769AA.toInt())
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Exporteert de lokaal opgeslagen fotogegevens, instellingen, cataloguscache en eventuele lokale bestanden en databases naar één ZIP-bestand. Beheersessiegegevens worden om veiligheidsredenen niet meegenomen; na een eventuele herinstallatie moet je opnieuw inloggen."
            textSize = 16f
            setTextColor(0xFF263746.toInt())
            setPadding(0, 0, 0, 24)
        })
        root.addView(Button(this).apply {
            text = "Back-up exporteren"
            setOnClickListener { createBackupFile() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Terug"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        setContentView(root)
    }

    private fun createBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "TijdBeeld-backup-${System.currentTimeMillis()}.zip")
        }
        startActivityForResult(intent, requestCreateBackup)
    }

    @Deprecated("Uses the document picker result API for compatibility with the #139 Android baseline")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCreateBackup || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Het gekozen bestand kan niet worden geopend")
            output.use { stream ->
                ZipOutputStream(stream).use { zip ->
                    addBackupJson(zip)
                    addDirectory(zip, File(filesDir, ""), "files/")
                    databaseList().forEach { name ->
                        val db = getDatabasePath(name)
                        if (db.isFile) addFile(zip, db, "databases/${db.name}")
                        val wal = File(db.path + "-wal")
                        val shm = File(db.path + "-shm")
                        if (wal.isFile) addFile(zip, wal, "databases/${wal.name}")
                        if (shm.isFile) addFile(zip, shm, "databases/${shm.name}")
                    }
                }
            }
            Toast.makeText(this, "Back-up succesvol geëxporteerd.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Back-up mislukt: ${e.message ?: "onbekende fout"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addBackupJson(zip: ZipOutputStream) {
        val prefs = getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE)
        val values = JSONObject()
        val excluded = setOf("admin_session_token", "admin_session_expires_at")
        prefs.all.forEach { (key, value) ->
            if (key in excluded) return@forEach
            val entry = JSONObject()
            when (value) {
                is String -> { entry.put("type", "string"); entry.put("value", value) }
                is Boolean -> { entry.put("type", "boolean"); entry.put("value", value) }
                is Int -> { entry.put("type", "int"); entry.put("value", value) }
                is Long -> { entry.put("type", "long"); entry.put("value", value) }
                is Float -> { entry.put("type", "float"); entry.put("value", value.toDouble()) }
                is Set<*> -> {
                    entry.put("type", "string_set")
                    val array = JSONArray()
                    value.filterIsInstance<String>().forEach { array.put(it) }
                    entry.put("value", array)
                }
                else -> return@forEach
            }
            values.put(key, entry)
        }
        val manifest = JSONObject()
            .put("format", "tijdbeeld-local-backup")
            .put("formatVersion", 1)
            .put("baseline", "build-139")
            .put("createdAt", System.currentTimeMillis())
            .put("preferencesFile", "shared_preferences.json")
            .put("filesDirectory", "files/")
            .put("databasesDirectory", "databases/")
            .put("excludedPreferences", JSONArray().put("admin_session_token").put("admin_session_expires_at"))
        putText(zip, "manifest.json", manifest.toString(2))
        putText(zip, "shared_preferences.json", values.toString(2))
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.exists() || !directory.isDirectory) return
        directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) addDirectory(zip, child, prefix + child.name + "/")
            else if (child.isFile) addFile(zip, child, prefix + child.name)
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun putText(zip: ZipOutputStream, entryName: String, content: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
