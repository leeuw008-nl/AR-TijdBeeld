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
import java.security.MessageDigest
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
            setBackgroundColor(0xFFF3F6F9.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(14), dp(6))
            setBackgroundColor(0xFF1769AA.toInt())
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Terug"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?")
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "App-versie"
        })
        header.addView(TextView(this).apply {
            text = "AR-TijdBeeld"
            textSize = 21f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        page.addView(TextView(this).apply {
            text = "Back-up en herstel"
            textSize = 24f
            setTextColor(0xFF1769AA.toInt())
            setPadding(0, 0, 0, dp(16))
        })
        page.addView(TextView(this).apply {
            text = "Een back-up bevat de lokale instellingen en fotogegevens, interne bestanden en databases. Beheersessies worden bewust niet meegenomen. Herstellen vervangt de overeenkomstige lokale gegevens door de inhoud van de gekozen back-up."
            textSize = 16f
            setTextColor(0xFF263746.toInt())
            setPadding(0, 0, 0, 24)
        })
        page.addView(Button(this).apply {
            text = "Back-up exporteren"
            setOnClickListener { createBackup() }
        }, LinearLayout.LayoutParams(-1, -2))
        page.addView(Button(this).apply {
            text = "Back-up importeren en herstellen"
            setOnClickListener { chooseBackup() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        root.addView(page, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

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
        val hashes = JSONObject()
        putText(zip, "shared_preferences.json", prefsJson.toString(2), hashes)
        addDirectory(zip, filesDir, "files/", hashes)
        databaseList().forEach { name ->
            val db = getDatabasePath(name)
            if (db.isFile) addFile(zip, db, "databases/${db.name}", hashes)
            listOf(File(db.path + "-wal"), File(db.path + "-shm")).filter { it.isFile }
                .forEach { addFile(zip, it, "databases/${it.name}", hashes) }
        }
        getExternalFilesDir(null)?.let { addDirectory(zip, it, "external_files/", hashes) }
        val manifest = JSONObject()
            .put("format", "tijdbeeld-local-backup")
            .put("formatVersion", 3)
            .put("baseline", "build-139")
            .put("createdAt", System.currentTimeMillis())
            .put("contents", JSONArray().put("shared_preferences.json").put("files/").put("databases/").put("external_files/"))
            .put("excludedPreferences", JSONArray().put("admin_session_token").put("admin_session_expires_at"))
            .put("sha256", hashes)
        putText(zip, "manifest.json", manifest.toString(2), JSONObject())
    }

    private fun restoreBackup(input: java.io.InputStream) {
        val stage = File(cacheDir, "tijdbeeld-restore-${System.currentTimeMillis()}")
        if (stage.exists()) stage.deleteRecursively()
        check(stage.mkdirs()) { "Tijdelijke herstelmap kan niet worden aangemaakt" }
        var total = 0L
        var manifest: JSONObject? = null
        var prefsJson: JSONObject? = null
        val actualHashes = JSONObject()
        val seen = HashSet<String>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory) { "Ongeldige mapvermelding in ZIP" }
                    val name = entry.name
                    check(seen.add(name)) { "Dubbel bestand in back-up: $name" }
                    check(!name.startsWith("/") && !name.contains("\\") &&
                        name.split('/').none { it == ".." || it.isEmpty() }) { "Ongeldig bestandspad in back-up" }
                    check(name == "manifest.json" || name == "shared_preferences.json" ||
                        name.startsWith("files/") || name.startsWith("databases/") ||
                        name.startsWith("external_files/")) { "Onbekend onderdeel in back-up: $name" }
                    if (name == "manifest.json" || name == "shared_preferences.json") {
                        val bytes = zip.readBytesBounded(2L * 1024L * 1024L)
                        total += bytes.size
                        check(total <= maxBackupBytes) { "Back-up is groter dan 256 MB" }
                        if (name == "manifest.json") manifest = JSONObject(String(bytes, Charsets.UTF_8))
                        else {
                            prefsJson = JSONObject(String(bytes, Charsets.UTF_8))
                            actualHashes.put(name, sha256(bytes))
                        }
                    } else {
                        val target = File(stage, name)
                        check(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Ongeldig bestandspad" }
                        target.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        target.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= maxBackupBytes) { "Back-up is groter dan 256 MB" }
                                digest.update(buffer, 0, count)
                                out.write(buffer, 0, count)
                            }
                        }
                        actualHashes.put(name, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                    }
                    zip.closeEntry()
                }
            }
            val m = manifest ?: error("manifest.json ontbreekt")
            check(m.optString("format") == "tijdbeeld-local-backup") { "Dit is geen TijdBeeld-back-up" }
            val formatVersion = m.optInt("formatVersion")
            check(formatVersion in 1..3) { "Niet-ondersteunde back-upversie" }
            check(m.optString("baseline") == "build-139") { "Deze back-up hoort niet bij de build-139 gegevensindeling" }
            if (formatVersion >= 3) {
                val expectedHashes = m.optJSONObject("sha256") ?: error("SHA-256-controle ontbreekt")
                check(expectedHashes.length() == actualHashes.length()) { "Back-up is onvolledig: aantal bestanden klopt niet" }
                val keys = expectedHashes.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    check(expectedHashes.optString(key) == actualHashes.optString(key)) {
                        "Back-upbestand beschadigd of gewijzigd: $key"
                    }
                }
            }
            val savedPrefs = prefsJson ?: error("Instellingenbestand ontbreekt")
            validatePreferences(savedPrefs)

            // Zorg dat ook lege mappen uit de back-up worden teruggezet; anders zouden
            // bestanden die na de back-up zijn aangemaakt ten onrechte blijven bestaan.
            listOf("files", "databases", "external_files").forEach { name ->
                check(File(stage, name).mkdirs() || File(stage, name).isDirectory) {
                    "Herstelmap kan niet worden voorbereid: $name"
                }
            }

            // Bewaar de huidige staat totdat alle mappen en voorkeuren succesvol zijn vervangen.
            val prefsBefore = preferencesToJson()
            val replacements = mutableListOf<Pair<File, File>>()
            try {
                listOf(
                    File(stage, "files") to filesDir,
                    File(stage, "databases") to getDatabasePath("placeholder").parentFile!!,
                    File(stage, "external_files") to getExternalFilesDir(null)
                ).forEach { (source, destination) ->
                    if (destination != null && source.exists()) {
                        replacements.add(replaceDirectoryTransactional(source, destination))
                    }
                }
                restorePreferences(savedPrefs)
            } catch (e: Exception) {
                try { restorePreferences(prefsBefore) } catch (_: Exception) { }
                replacements.asReversed().forEach { (oldDir, destination) ->
                    destination.deleteRecursively()
                    if (oldDir.exists()) oldDir.renameTo(destination)
                }
                throw e
            }
            replacements.forEach { (oldDir, _) -> oldDir.deleteRecursively() }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun preferencesToJson(): JSONObject {
        val result = JSONObject()
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
            }
            result.put(key, item)
        }
        return result
    }

    private fun validatePreferences(json: JSONObject) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = json.getJSONObject(key)
            when (item.getString("type")) {
                "string" -> item.getString("value")
                "boolean" -> item.getBoolean("value")
                "int" -> item.getInt("value")
                "long" -> item.getLong("value")
                "float" -> item.getDouble("value")
                "string_set" -> item.getJSONArray("value")
                else -> error("Onbekend voorkeurstype voor $key")
            }
        }
    }

    /** Copy to a sibling staging directory so the final rename stays on the destination filesystem. */
    private fun replaceDirectoryTransactional(source: File, destination: File): Pair<File, File> {
        val staged = File(destination.parentFile, destination.name + ".restore-new")
        val previous = File(destination.parentFile, destination.name + ".restore-old")
        staged.deleteRecursively()
        previous.deleteRecursively()
        check(staged.mkdirs()) { "Herstelmap kan niet worden aangemaakt: ${destination.name}" }
        copyDirectory(source, staged)
        if (destination.exists()) check(destination.renameTo(previous)) {
            "Huidige map kan niet veilig worden bewaard: ${destination.name}"
        }
        if (!staged.renameTo(destination)) {
            previous.takeIf { it.exists() }?.renameTo(destination)
            staged.deleteRecursively()
            error("Herstellen van map mislukt: ${destination.name}")
        }
        return previous to destination
    }

    private fun copyDirectory(source: File, destination: File) {
        source.listFiles()?.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) {
                check(target.mkdirs()) { "Map kan niet worden aangemaakt: ${child.name}" }
                copyDirectory(child, target)
            } else {
                child.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
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

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String, hashes: JSONObject) {
        if (!directory.exists() || !directory.isDirectory) return
        directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) addDirectory(zip, child, prefix + child.name + "/", hashes)
            else if (child.isFile) addFile(zip, child, prefix + child.name, hashes)
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, path: String, hashes: JSONObject) {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                zip.write(buffer, 0, count)
            }
        }
        zip.closeEntry()
        hashes.put(path, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun putText(zip: ZipOutputStream, path: String, value: String, hashes: JSONObject) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
        hashes.put(path, sha256(bytes))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
