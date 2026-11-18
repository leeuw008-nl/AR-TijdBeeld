package nl.leeuw008.tijdbeeld

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Button
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class MapPhoto(
    val id: String,
    val title: String,
    val imageUrl: String,
    val objectLat: Double,
    val objectLng: Double,
    val cameraLat: Double,
    val cameraLng: Double,
    val imageWidthMeters: Float
)

class PhotoMapActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var photos: List<MapPhoto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return handleMapUrl(request.url)
                }
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleMapUrl(Uri.parse(url))
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            text = "TijdBeeld • fotolocaties ophalen…"
            textSize = 13f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            setPadding(18, 14, 18, 14)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val back = Button(this).apply {
            text = "‹"
            textSize = 30f
            minWidth = dp(52)
            minimumWidth = dp(52)
            setPadding(0, 0, 0, 0)
            contentDescription = "Terug"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(dp(52), dp(48), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(12)
            topMargin = dp(8)
        })
        setContentView(root)
        loadCatalog()
    }

    private fun loadCatalog() {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(PUBLIC_CATALOG_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Catalogus niet bereikbaar (HTTP ${connection.responseCode})")
                }
                val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val array = root.getJSONArray("photos")
                val loaded = mutableListOf<MapPhoto>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val title = item.optString("title").trim()
                    val imageUrl = item.optString("imageUrl").trim()
                    val lat = item.optDouble("objectLat", Double.NaN)
                    val lng = item.optDouble("objectLng", Double.NaN)
                    if (title.isBlank() || imageUrl.isBlank() || !lat.isFinite() || !lng.isFinite()) continue
                    loaded.add(MapPhoto(
                        item.optString("id", "photo-$i"), title, imageUrl, lat, lng,
                        item.optDouble("cameraLat", lat), item.optDouble("cameraLng", lng),
                        item.optDouble("imageWidthMeters", 3.0).toFloat().coerceIn(1f, 12f)
                    ))
                }
                if (loaded.isEmpty()) throw IllegalStateException("Geen geldige fotolocaties gevonden")
                runOnUiThread {
                    photos = loaded
                    status.text = "TijdBeeld • ${loaded.size} historische fotolocaties"
                    showMap()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Fotolocaties konden niet worden geladen. Controleer je internetverbinding." }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showMap() {
        val markers = JSONArray()
        photos.filterNot { it.title.trim() == OMMERSCHANS_PHOTO_TITLE }.forEach { photo ->
            markers.put(JSONObject().apply {
                put("id", photo.id)
                put("title", photo.title)
                put("lat", photo.objectLat)
                put("lng", photo.objectLng)
            })
        }
        val modelFootprint = JSONArray(OMMERSCHANS_FOOTPRINT_JSON)
        val html = """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
            <style>html,body,#map{height:100%;width:100%;margin:0;padding:0} .leaflet-popup-content{font-size:15px} button{background:#174a7e;color:white;border:0;border-radius:5px;padding:9px 12px;font-size:14px} .leaflet-control-zoom a{width:38px!important;height:38px!important;line-height:38px!important;font-size:24px!important} .leaflet-control-zoomlevel{background:#fff;padding:5px 8px;border:2px solid rgba(0,0,0,.2);border-radius:4px;font:bold 12px/18px Arial;color:#333;text-align:center;min-width:42px}</style>
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script></head>
            <body><div id="map"></div><script>
            const photos = $markers;
            const modelFootprint = $modelFootprint;
            const map = L.map('map',{zoomControl:false});
            L.control.zoom({position:'topright'}).addTo(map);
            const zoomLevel = L.control({position:'topright'});
            zoomLevel.onAdd = function() {
              this._div = L.DomUtil.create('div','leaflet-control-zoomlevel');
              this._div.textContent = 'Zoom ' + map.getZoom();
              return this._div;
            };
            zoomLevel.addTo(map);
            map.on('zoomend', () => {
              const el = zoomLevel.getContainer();
              if (el) el.textContent = 'Zoom ' + map.getZoom();
            });
            L.tileLayer('https://service.pdok.nl/brt/achtergrondkaart/wmts/v2_0/standaard/EPSG:3857/{z}/{x}/{y}.png', {
              minZoom: 6, maxZoom: 19,
              attribution: 'Kaartgegevens © <a href="https://www.kadaster.nl">Kadaster / PDOK</a>'
            }).addTo(map);
            const bounds = [];
            modelFootprint.forEach(rings => {
              const polygonRings = rings.map(ring => ring.map(point => [point[0], point[1]]));
              const footprint = L.polygon(polygonRings, {
                color: '#174a7e', weight: 2, opacity: 0.95,
                fillColor: '#398bd0', fillOpacity: 0.38
              }).addTo(map);
              footprint.bindPopup('<strong>1900 - Ommerschans Hoofdgebouw</strong><br>Geprojecteerde contour van het 3D-model');
              footprint.getLatLngs()[0].forEach(point => bounds.push([point.lat, point.lng]));
            });
            photos.forEach(p => {
              const marker = L.marker([p.lat,p.lng]).addTo(map);
              const box = document.createElement('div');
              const title = document.createElement('strong');
              title.textContent = p.title;
              const button = document.createElement('button');
              button.textContent = 'Bekijk historische foto in AR';
              button.style.marginTop = '8px';
              button.onclick = () => { window.location.href = 'https://tijdbeeld.local/open?id=' + encodeURIComponent(p.id); };
              box.appendChild(title); box.appendChild(document.createElement('br')); box.appendChild(button);
              marker.bindPopup(box);
              bounds.push([p.lat,p.lng]);
            });
            if (bounds.length === 1) map.setView(bounds[0],16);
            else map.fitBounds(bounds,{padding:[35,35],maxZoom:16});
            </script></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://tijdbeeld.local/", html, "text/html", "UTF-8", null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun handleMapUrl(uri: Uri): Boolean {
        if (uri.host != "tijdbeeld.local" || uri.path != "/open") return false
        val id = uri.getQueryParameter("id") ?: return true
        val photo = photos.firstOrNull { it.id == id } ?: return true
        if (photo.title.trim() == OMMERSCHANS_PHOTO_TITLE) {
            startActivity(Intent(this, ModelArActivity::class.java))
            return true
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
        return true
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val OMMERSCHANS_PHOTO_TITLE = "1900 - Ommerschans Hoofdgebouw"
        private const val OMMERSCHANS_FOOTPRINT_JSON = """[[[52.58688878,6.39097249],[52.58684427,6.39105918],[52.58691704,6.39252954],[52.58696826,6.39260225],[52.58785908,6.39248589],[52.58790453,6.39239936],[52.58783521,6.39092233],[52.58778219,6.39085154],[52.58688878,6.39097249]],[[52.58710027,6.39227125],[52.58769773,6.3921902],[52.58768949,6.39202628],[52.58709202,6.39210734],[52.58710027,6.39227125]],[[52.58724032,6.39132224],[52.58760182,6.39127566],[52.58759722,6.39117948],[52.58723572,6.39122606],[52.58724032,6.39132224]],[[52.58740627,6.39190634],[52.58740143,6.39180197],[52.58737946,6.39180507],[52.58737695,6.39174885],[52.587367,6.39175021],[52.58736978,6.39180599],[52.58734772,6.3918088],[52.58735292,6.39191342],[52.58740627,6.39190634]],[[52.58772512,6.39211552],[52.58775695,6.39211141],[52.58774802,6.3919251],[52.58771619,6.39192922],[52.58772512,6.39211552]],[[52.58769331,6.39143519],[52.58772496,6.3914312],[52.58771627,6.39124528],[52.58768462,6.39124927],[52.58769331,6.39143519]],[[52.58700197,6.39152846],[52.58703367,6.39152434],[52.58702474,6.39133886],[52.58699304,6.39134297],[52.58700197,6.39152846]],[[52.58703599,6.39220766],[52.58706709,6.39220359],[52.58705808,6.39201761],[52.58702697,6.39202168],[52.58703599,6.39220766]]]"""
        private const val PUBLIC_CATALOG_URL =
            "https://tijdbeeld.leeuw008.workers.dev/photos"
    }
}
