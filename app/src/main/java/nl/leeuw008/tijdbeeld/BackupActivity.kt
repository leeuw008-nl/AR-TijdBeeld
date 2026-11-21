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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Local backup/restore for the storage used by the build #139 app. */
class BackupActivity : Activity() {
    private val createRequest = 7301
    private val openRequest = 7302
    private val excludedPrefs = setOf("admin_session_token", "admin_session_expires_at")
    private val maxBackupBytes = 256L * 1024L * 1024L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF3F6F9.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Back-up en herstel"
            textSize = 24f
            setTextColor(0xFF1769AA.toInt())
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Een back-up bevat de lokale instellingen en fotogegevens, interne bestanden en databases. Beheersessies worden bewust niet meegenomen. Herstellen vervangt de overeenkomstige lokale gegevens door de inhoud van de gekozen back-up."
            textSize = 16f
            setTextColor(0xFF263746.toInt())
            setPadding(0, 0, 0, 24)
        })
        root.addView(Button(this).apply {
            text = "Back-up exporteren"
            setOnClickListener { createBackup() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Back-up importeren en herstellen"
            setOnClickListener { chooseBackup() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        root.addView(Button(this).apply {
            text = "Terug"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        setContentView(root)
    }

    private fun createBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "TijdBeeld-backup-${System.currentTimeMillis()}.zip")
        }
        startActivityForResult(intent, createRequest)
    }

    private fun chooseBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, openRequest)
    }

    @Deprecated("Document picker API retained for compatibility with the build #139 Android baseline")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                createRequest -> {
                    val output = contentResolver.openOutputStream(uri)
                        ?: error("Het gekozen bestand kan niet worden geopend")
                    output.use { stream ->
                        ZipOutputStream(stream).use { zip -> writeBackup(zip) }
                    }
                    toast("Back-up succesvol geëxporteerd.")
                }
                openRequest -> {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Back-up terugzetten?")
                        .setMessage("De huidige lokale instellingen, fotogegevens, bestanden en databases worden vervangen door de gegevens uit deze back-up. Beheersessies blijven buiten de back-up.")
                        .setNegativeButton("Annuleren", null)
                        .setPositiveButton("Herstellen") { _, _ ->
                            try {
                                contentResolver.openInputStream(uri)?.use { restoreBackup(it) }
                                    ?: error("De back-up kan niet worden gelezen")
                                toast("Back-up hersteld. Sluit en open de app opnieuw om alle gegevens opnieuw te laden.")
                            } catch (e: Exception) {
                                toast("Herstellen mislukt: ${e.message ?: "onbekende fout"}")
                            }
                        }.show()
                }
            }
        } catch (e: Exception) {
            toast("Back-upbewerking mislukt: ${e.message ?: "onbekende fout"}")
        }
    }

    private fun writeBackup(zip: ZipOutputStream) {
        val prefsJson = JSONObject()
        getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE).all.forEach { (key, value) ->
            if (key in excludedPrefs) return@forEach
            val item = JSONObject()
            when (value) {
                is String -> { item.put("type", "string"); item.put("value", value) }
                is Boolean -> { item.put("type", "boolean"); item.put("value", value) }
                is Int -> { item.put("type", "int"); item.put("value", value) }
                is Long -> { item.put("type", "long"); item.put("value", value) }
                is Float -> { item.put("type", "float"); item.put("value", value.toDouble()) }
                is Set<*> -> {
                    item.put("type", "string_set")
                    val array = JSONArray()
                    value.filterIsInstance<String>().forEach { array.put(it) }
                    item.put("value", array)
                }
                else -> return@forEach
            }
            prefsJson.put(key, item)
        }
        val manifest = JSONObject()
            .put("format", "tijdbeeld-local-backup")
            .put("formatVersion", 2)
            .put("baseline", "build-139")
            .put("createdAt", System.currentTimeMillis())
            .put("contents", JSONArray().put("shared_preferences.json").put("files/").put("databases/").put("external_files/"))
            .put("excludedPreferences", JSONArray().put("admin_session_token").put("admin_session_expires_at"))
        putText(zip, "manifest.json", manifest.toString(2))
        putText(zip, "shared_preferences.json", prefsJson.toString(2))
        addDirectory(zip, filesDir, "files/")
        databaseList().forEach { name ->
            val db = getDatabasePath(name)
            if (db.isFile) addFile(zip, db, "databases/${db.name}")
            listOf(File(db.path + "-wal"), File(db.path + "-shm")).filter { it.isFile }
                .forEach { addFile(zip, it, "databases/${it.name}") }
        }
        getExternalFilesDir(null)?.let { addDirectory(zip, it, "external_files/") }
    }

    private fun restoreBackup(input: java.io.InputStream) {
        val stage = File(cacheDir, "tijdbeeld-restore-${System.currentTimeMillis()}")
        if (stage.exists()) stage.deleteRecursively()
        check(stage.mkdirs()) { "Tijdelijke herstelmap kan niet worden aangemaakt" }
        var total = 0L
        var manifest: JSONObject? = null
        var prefsJson: JSONObject? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory) { "Mappen als losse ZIP-vermelding worden niet ondersteund" }
                    val name = entry.name
                    check(!name.startsWith("/") && !name.contains("\\") &&
                        name.split('/').none { it == ".." || it.isEmpty() }) { "Ongeldig bestandspad in back-up" }
                    check(name == "manifest.json" || name == "shared_preferences.json" ||
                        name.startsWith("files/") || name.startsWith("databases/") ||
                        name.startsWith("external_files/")) { "Onbekend onderdeel in back-up: $name" }
                    if (name == "manifest.json" || name == "shared_preferences.json") {
                        val bytes = zip.readBytesBounded(2L * 1024L * 1024L)
                        total += bytes.size
                        if (name == "manifest.json") manifest = JSONObject(String(bytes, Charsets.UTF_8))
                        else prefsJson = JSONObject(String(bytes, Charsets.UTF_8))
                    } else {
                        val target = File(stage, name)
                        check(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Ongeldig bestandspad" }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= maxBackupBytes) { "Back-up is groter dan 256 MB" }
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val m = manifest ?: error("manifest.json ontbreekt")
            check(m.optString("format") == "tijdbeeld-local-backup") { "Dit is geen TijdBeeld-back-up" }
            check(m.optInt("formatVersion") in 1..2) { "Niet-ondersteunde back-upversie" }
            val savedPrefs = prefsJson ?: error("Instellingenbestand ontbreekt")
            restorePreferences(savedPrefs)
            replaceDirectory(File(stage, "files"), filesDir)
            replaceDirectory(File(stage, "databases"), getDatabasePath("placeholder").parentFile!!)
            getExternalFilesDir(null)?.let { replaceDirectory(File(stage, "external_files"), it) }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun restorePreferences(json: JSONObject) {
        val editor = getSharedPreferences("tijdbeeld_admin", MODE_PRIVATE).edit().clear()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in excludedPrefs) continue
            val item = json.getJSONObject(key)
            when (item.getString("type")) {
                "string" -> editor.putString(key, item.optString("value"))
                "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                "int" -> editor.putInt(key, item.getInt("value"))
                "long" -> editor.putLong(key, item.getLong("value"))
                "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                "string_set" -> {
                    val array = item.getJSONArray("value")
                    val values = (0 until array.length()).map { array.getString(it) }.toSet()
                    editor.putStringSet(key, values)
                }
                else -> error("Onbekend voorkeurstype voor $key")
            }
        }
        check(editor.commit()) { "Instellingen konden niet worden opgeslagen" }
    }

    private fun replaceDirectory(source: File, destination: File) {
        if (!source.exists()) return
        val backup = File(destination.parentFile, destination.name + ".before-restore")
        backup.deleteRecursively()
        if (destination.exists()) check(destination.renameTo(backup)) { "Bestaande map kan niet veilig worden verplaatst: ${destination.name}" }
        if (!source.renameTo(destination)) {
            backup.takeIf { it.exists() }?.renameTo(destination)
            error("Herstel van map mislukt: ${destination.name}")
        }
        backup.deleteRecursively()
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.exists() || !directory.isDirectory) return
        directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) addDirectory(zip, child, prefix + child.name + "/")
            else if (child.isFile) addFile(zip, child, prefix + child.name)
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun putText(zip: ZipOutputStream, path: String, value: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun java.util.zip.ZipInputStream.readBytesBounded(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var size = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            size += count
            check(size <= limit) { "Metadata in back-up is te groot" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
