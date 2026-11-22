package nl.leeuw008.tijdbeeld

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Minimal GLB 2.0 triangle renderer. Geometry is kept in model metres. */
class OmmerschansModelRenderer(private val context: Context, private val status: (String) -> Unit, private val standaloneMode: Boolean = false) : GLSurfaceView.Renderer {
    @Volatile private var session: Session? = null
    @Volatile private var lastStatus = ""
    @Volatile private var modelData: List<Part>? = null
    @Volatile private var embeddedImages: List<ByteArray> = emptyList()
    private val glModelTextures = mutableMapOf<Int, Int>()
    @Volatile private var loadStarted = false
    @Volatile private var anchor: Anchor? = null
    @Volatile private var targetLatitude = 52.58737559898257
    @Volatile private var targetLongitude = 6.391727308884152
    private var anchorRequested = false
    private var standaloneTransformReady = false
    private val standaloneModel = FloatArray(16)
    private val standaloneRotation = FloatArray(16)
    private val standaloneRotatedModel = FloatArray(16)
    private val standaloneTranslation = FloatArray(16)
    @Volatile private var standaloneOffsetX = 0f
    @Volatile private var standaloneOffsetY = 0f
    @Volatile private var standaloneRotationX = 0f
    @Volatile private var standaloneRotationY = 0f
    @Volatile private var standaloneCameraDistance = 7.5f
    @Volatile private var standaloneZoomSensitivity = 1.0f
    private var cameraTexture = 0
    private var cameraProgram = 0
    private var modelProgram = 0
    private var cameraPos = 0
    private var cameraUv = 0
    private var cameraSampler = 0
    private var modelPos = 0
    private var modelNormal = 0
    private var modelUv = 0
    private var modelMvp = 0
    private var modelColor = 0
    private var modelSampler = 0
    private var modelUseTexture = 0
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private var width = 0
    private var height = 0
    private var displayRotation = android.view.Surface.ROTATION_0
    private var geometryChanged = true
    private var textureSession: Session? = null
    private val quad = floats(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val uv = floats(FloatArray(8))
    private val baseUv = floats(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))

    data class Part(val vertices: FloatBuffer, val indices: ShortBuffer?, val count: Int, val color: FloatArray, val normals: FloatBuffer, val uvs: FloatBuffer?, val textureIndex: Int?, val transparent: Boolean, val doubleSided: Boolean)

    private fun report(message: String) {
        if (message != lastStatus) { lastStatus = message; status(message) }
    }

    fun setSession(value: Session) {
        session = value
        textureSession = null
        geometryChanged = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f,0f,0f,1f)
        cameraProgram = program(CAMERA_VS, CAMERA_FS)
        cameraPos = GLES20.glGetAttribLocation(cameraProgram, "aPos")
        cameraUv = GLES20.glGetAttribLocation(cameraProgram, "aUv")
        cameraSampler = GLES20.glGetUniformLocation(cameraProgram, "sCamera")
        modelProgram = program(MODEL_VS, MODEL_FS)
        modelPos = GLES20.glGetAttribLocation(modelProgram, "aPos")
        modelNormal = GLES20.glGetAttribLocation(modelProgram, "aNormal")
        modelUv = GLES20.glGetAttribLocation(modelProgram, "aUv")
        modelMvp = GLES20.glGetUniformLocation(modelProgram, "uMvp")
        modelColor = GLES20.glGetUniformLocation(modelProgram, "uColor")
        modelSampler = GLES20.glGetUniformLocation(modelProgram, "uTexture")
        modelUseTexture = GLES20.glGetUniformLocation(modelProgram, "uUseTexture")
        glModelTextures.clear()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        loadModel()
    }

    fun setDisplayRotation(rotation: Int) { displayRotation = rotation; geometryChanged = true }

    fun setZoomSensitivity(value: Float) {
        if (standaloneMode && value.isFinite()) standaloneZoomSensitivity = value.coerceIn(0f, 20f)
    }

    /** Move the model in the viewing plane with a two-finger drag. */
    fun panStandalone(deltaX: Float, deltaY: Float, viewportWidth: Int, viewportHeight: Int) {
        if (!standaloneMode || !deltaX.isFinite() || !deltaY.isFinite()) return
        val viewport = minOf(viewportWidth, viewportHeight).coerceAtLeast(1).toFloat()
        val sensitivity = 0.15f + kotlin.math.sqrt(standaloneZoomSensitivity) * 1.70f
        val worldPerPixel = standaloneCameraDistance / viewport * 0.35f * sensitivity
        standaloneOffsetX += deltaX * worldPerPixel
        standaloneOffsetY -= deltaY * worldPerPixel
    }

    fun rotateStandalone(deltaX: Float, deltaY: Float) {
        if (!standaloneMode) return
        // Reduce drag movement progressively as the camera gets closer to the model.
        val zoomDamping = (standaloneCameraDistance / 7.5f).coerceIn(0.08f, 1f)
        // The on-screen sensitivity control affects both pinch zoom and drag rotation.
        // 50% keeps the original drag speed; lower values allow precise movement when zoomed in.
        val userSensitivity = 0.15f + kotlin.math.sqrt(standaloneZoomSensitivity) * 1.70f
        val dragScale = 0.045f * zoomDamping * userSensitivity
        standaloneRotationY = (standaloneRotationY + deltaX * dragScale) % 360f
        standaloneRotationX = (standaloneRotationX + deltaY * dragScale) % 360f
    }

    fun zoomStandalone(factor: Float) {
        if (standaloneMode && factor.isFinite() && factor > 0f) {
            val exponent = 0.15f + kotlin.math.sqrt(standaloneZoomSensitivity) * 0.85f
            standaloneCameraDistance = (standaloneCameraDistance / factor.coerceIn(0.1f, 10f).let { Math.pow(it.toDouble(), exponent.toDouble()).toFloat() }).coerceIn(0.65f, 24f)
        }
    }


    fun setGeoTarget(latitude: Double, longitude: Double) {
        if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            targetLatitude = latitude
            targetLongitude = longitude
            anchor = null
            anchorRequested = false
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0,0,w,h)
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (standaloneMode) {
            drawStandalone()
            return
        }
        val s = session ?: return
        try {
            if (textureSession !== s) {
                s.setCameraTextureNames(intArrayOf(cameraTexture))
                textureSession = s
            }
            if (geometryChanged && width > 0 && height > 0) {
                s.setDisplayGeometry(displayRotation, width, height)
                geometryChanged = false
            }
            val frame = s.update()
            frame.transformDisplayUvCoords(baseUv, uv)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(cameraProgram)
            quad.position(0); uv.position(0)
            GLES20.glEnableVertexAttribArray(cameraPos)
            GLES20.glVertexAttribPointer(cameraPos,2,GLES20.GL_FLOAT,false,0,quad)
            GLES20.glEnableVertexAttribArray(cameraUv)
            GLES20.glVertexAttribPointer(cameraUv,2,GLES20.GL_FLOAT,false,0,uv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTexture)
            GLES20.glUniform1i(cameraSampler,0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GLES20.glDisableVertexAttribArray(cameraPos); GLES20.glDisableVertexAttribArray(cameraUv)

            val earth = s.earth
            if (earth == null || earth.earthState != Earth.EarthState.ENABLED ||
                earth.trackingState != TrackingState.TRACKING) {
                report("ARCore zoekt GPS-, kompas- en Geospatial-tracking…")
                return
            }
            val pose = earth.cameraGeospatialPose
            if (pose.horizontalAccuracy > 10.0 || pose.headingAccuracy > 15.0) {
                report("Wacht op nauwkeurige locatie. GPS-afwijking: ${pose.horizontalAccuracy.toInt()} m.")
                return
            }
            if (!anchorRequested && anchor == null) {
                anchorRequested = true
                report("Locatie gevonden. Het 3D-model wordt op de bouwlocatie geplaatst…")
                // GLB origin: courtyard centre at the coordinates supplied by the selected photo.
                // Axes in the delivered model are +X east, +Y up and -Z true north.
                // ARCore Geospatial uses east/up/south, so identity rotation aligns the axes;
                // the building's 4.4-degree orientation is already present in the model geometry.
                earth.resolveAnchorOnTerrainAsync(
                    targetLatitude, targetLongitude, 0.0,
                    0f, 0f, 0f, 1f
                ) { resolved, state ->
                    if (state == Anchor.TerrainAnchorState.SUCCESS && resolved != null) {
                        anchor = resolved
                        report("3D-model geplaatst; schaal en oriëntatie moeten ter plaatse worden gecontroleerd.")
                    } else {
                        anchorRequested = false
                        report("Plaatsen van het 3D-model is mislukt: $state")
                    }
                }
            }
            val a = anchor
            val parts = modelData
            if (a == null || a.trackingState != TrackingState.TRACKING || parts == null) return
            frame.camera.getViewMatrix(view,0)
            frame.camera.getProjectionMatrix(projection,0,0.1f,1000f)
            a.pose.toMatrix(model,0)
            Matrix.multiplyMM(mv,0,view,0,model,0)
            Matrix.multiplyMM(mvp,0,projection,0,mv,0)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glUseProgram(modelProgram)
            GLES20.glUniformMatrix4fv(modelMvp,1,false,mvp,0)
            drawModelParts(parts)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
        } catch (e: Exception) {
            report("3D-camera fout: ${e.message ?: e.javaClass.simpleName}")
        }
    }


    private fun drawStandalone() {
        GLES20.glClearColor(0.48f, 0.56f, 0.62f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val parts = modelData
        if (parts == null) {
            report("3D-model wordt geladen…")
            return
        }
        if (!standaloneTransformReady) {
            var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
            for (part in parts) {
                val vertices = part.vertices
                var i = 0
                while (i + 2 < vertices.limit()) {
                    val x = vertices.get(i); val y = vertices.get(i + 1); val z = vertices.get(i + 2)
                    minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
                    maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
                    i += 3
                }
            }
            val extent = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(0.001f)
            val scale = 2.8f / extent
            Matrix.setIdentityM(standaloneModel, 0)
            Matrix.scaleM(standaloneModel, 0, scale, scale, scale)
            Matrix.translateM(standaloneModel, 0, -(minX + maxX) / 2f, -(minY + maxY) / 2f, -(minZ + maxZ) / 2f)
            standaloneTransformReady = true
            report("3D-model geladen — vrij bekijken")
        }
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projection, 0, 45f, aspect.coerceAtLeast(0.1f), 0.1f, 100f)
        Matrix.setLookAtM(view, 0, 0f, standaloneCameraDistance * 0.24f, standaloneCameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setRotateM(standaloneRotation, 0, standaloneRotationX, 1f, 0f, 0f)
        Matrix.rotateM(standaloneRotation, 0, standaloneRotationY, 0f, 1f, 0f)
        Matrix.setIdentityM(standaloneTranslation, 0)
        Matrix.translateM(standaloneTranslation, 0, standaloneOffsetX, standaloneOffsetY, 0f)
        Matrix.multiplyMM(standaloneRotatedModel, 0, standaloneTranslation, 0, standaloneRotation, 0)
        Matrix.multiplyMM(mv, 0, standaloneRotatedModel, 0, standaloneModel, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(modelProgram)
        GLES20.glUniformMatrix4fv(modelMvp, 1, false, mvp, 0)
        drawModelParts(parts)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun uploadModelTexture(imageIndex: Int): Int {
        glModelTextures[imageIndex]?.let { return it }
        val data = embeddedImages.getOrNull(imageIndex) ?: return 0
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inScaled = false }) ?: return 0
        fun powerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = result shl 1
            return result
        }
        val uploadBitmap = if ((bitmap.width and (bitmap.width - 1)) != 0 || (bitmap.height and (bitmap.height - 1)) != 0) {
            Bitmap.createScaledBitmap(bitmap, powerOfTwo(bitmap.width), powerOfTwo(bitmap.height), true)
        } else bitmap
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        if (uploadBitmap !== bitmap) uploadBitmap.recycle()
        bitmap.recycle()
        glModelTextures[imageIndex] = id
        return id
    }

    private fun drawModelParts(parts: List<Part>) {
        GLES20.glUseProgram(modelProgram)
        GLES20.glUniform1i(modelSampler, 0)
        for (part in parts.sortedBy { it.transparent }) {
            if (part.doubleSided) GLES20.glDisable(GLES20.GL_CULL_FACE) else GLES20.glEnable(GLES20.GL_CULL_FACE)
            if (part.transparent || part.color[3] < 0.999f) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
            } else {
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glDepthMask(true)
            }
            part.vertices.position(0)
            GLES20.glEnableVertexAttribArray(modelPos)
            GLES20.glVertexAttribPointer(modelPos, 3, GLES20.GL_FLOAT, false, 0, part.vertices)
            part.normals.position(0)
            GLES20.glEnableVertexAttribArray(modelNormal)
            GLES20.glVertexAttribPointer(modelNormal, 3, GLES20.GL_FLOAT, false, 0, part.normals)
            GLES20.glUniform4fv(modelColor, 1, part.color, 0)
            val textureId = part.textureIndex?.let { uploadModelTexture(it) } ?: 0
            if (textureId != 0 && part.uvs != null) {
                part.uvs.position(0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glEnableVertexAttribArray(modelUv)
                GLES20.glVertexAttribPointer(modelUv, 2, GLES20.GL_FLOAT, false, 0, part.uvs)
                GLES20.glUniform1i(modelUseTexture, 1)
            } else {
                GLES20.glDisableVertexAttribArray(modelUv)
                GLES20.glUniform1i(modelUseTexture, 0)
            }
            if (part.indices != null) {
                part.indices.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, part.count, GLES20.GL_UNSIGNED_SHORT, part.indices)
            } else {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, part.count)
            }
            GLES20.glDisableVertexAttribArray(modelPos)
            GLES20.glDisableVertexAttribArray(modelNormal)
            GLES20.glDisableVertexAttribArray(modelUv)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun loadModel() {
        if (loadStarted) return
        loadStarted = true
        Thread {
            try {
                report("Lokaal 3D-modelbestand wordt geladen…")
                val bytes = context.assets.open("ommerschans.glb").use { it.readBytes() }
                val parsed = parseGlb(bytes)
                modelData = parsed
                report("3D-model geladen: ${parsed.size} onderdelen")
            } catch (e: Exception) {
                loadStarted = false
                report("FOUT bij laden 3D-model: ${e.message}")
            }
        }.start()
    }

    private fun parseGlb(bytes: ByteArray): List<Part> {
        require(bytes.size >= 20 && bytes[0] == 0x67.toByte() && bytes[1] == 0x6c.toByte() &&
            bytes[2] == 0x54.toByte() && bytes[3] == 0x46.toByte()) { "Geen geldig GLB-bestand" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(4) == 2) { "Alleen GLB 2.0 wordt ondersteund" }
        var offset = 12
        var json: JSONObject? = null
        var bin: ByteBuffer? = null
        while (offset + 8 <= bytes.size) {
            val length = header.getInt(offset)
            val type = header.getInt(offset + 4)
            offset += 8
            require(length >= 0 && offset + length <= bytes.size) { "Ongeldige GLB-chunk" }
            if (type == 0x4E4F534A) {
                json = JSONObject(String(bytes, offset, length, Charsets.UTF_8).trim())
            } else if (type == 0x004E4942) {
                bin = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            offset += length
        }
        val root = json ?: error("GLB bevat geen JSON-chunk")
        val binary = bin ?: error("GLB bevat geen binaire chunk")
        val views = root.getJSONArray("bufferViews")
        val accessors = root.getJSONArray("accessors")
        val meshes = root.getJSONArray("meshes")
        val materials = root.optJSONArray("materials")
        val textures = root.optJSONArray("textures")
        val images = root.optJSONArray("images")
        val decodedImages = mutableListOf<ByteArray>()
        if (images != null) for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            val viewIndex = image.optInt("bufferView", -1)
            if (viewIndex >= 0 && viewIndex < views.length()) {
                val imageView = views.getJSONObject(viewIndex)
                val start = imageView.optInt("byteOffset", 0)
                val length = imageView.getInt("byteLength")
                require(start >= 0 && length >= 0 && start + length <= binary.limit()) { "Ongeldige afbeeldingsdata in GLB" }
                decodedImages.add(ByteArray(length) { binary.get(start + it) })
            } else decodedImages.add(ByteArray(0))
        }
        embeddedImages = decodedImages
        val nodes = root.optJSONArray("nodes")
        val parts = mutableListOf<Part>()

        fun nodeMatrix(node: JSONObject): FloatArray {
            if (node.has("matrix")) {
                val values = node.getJSONArray("matrix")
                require(values.length() == 16) { "Ongeldige node-matrix" }
                return FloatArray(16) { values.getDouble(it).toFloat() }
            }
            val t = node.optJSONArray("translation")
            val r = node.optJSONArray("rotation")
            val sc = node.optJSONArray("scale")
            val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
            val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
            val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
            val x = r?.optDouble(0, 0.0) ?: 0.0
            val y = r?.optDouble(1, 0.0) ?: 0.0
            val z = r?.optDouble(2, 0.0) ?: 0.0
            val w = r?.optDouble(3, 1.0) ?: 1.0
            val sx = sc?.optDouble(0, 1.0)?.toFloat() ?: 1f
            val sy = sc?.optDouble(1, 1.0)?.toFloat() ?: 1f
            val sz = sc?.optDouble(2, 1.0)?.toFloat() ?: 1f
            return floatArrayOf(
                (1.0 - 2.0*y*y - 2.0*z*z).toFloat()*sx,
                (2.0*x*y + 2.0*z*w).toFloat()*sx,
                (2.0*x*z - 2.0*y*w).toFloat()*sx, 0f,
                (2.0*x*y - 2.0*z*w).toFloat()*sy,
                (1.0 - 2.0*x*x - 2.0*z*z).toFloat()*sy,
                (2.0*y*z + 2.0*x*w).toFloat()*sy, 0f,
                (2.0*x*z + 2.0*y*w).toFloat()*sz,
                (2.0*y*z - 2.0*x*w).toFloat()*sz,
                (1.0 - 2.0*x*x - 2.0*y*y).toFloat()*sz, 0f,
                tx, ty, tz, 1f
            )
        }

        fun readComponent(source: ByteBuffer, position: Int, componentType: Int, normalized: Boolean): Float {
            return when (componentType) {
                5126 -> source.getFloat(position)
                5123 -> {
                    val v = source.getShort(position).toInt() and 0xffff
                    if (normalized) v / 65535f else v.toFloat()
                }
                5122 -> {
                    val v = source.getShort(position).toShort().toInt()
                    if (normalized) (v / 32767f).coerceAtLeast(-1f) else v.toFloat()
                }
                5121 -> {
                    val v = source.get(position).toInt() and 0xff
                    if (normalized) v / 255f else v.toFloat()
                }
                5120 -> {
                    val v = source.get(position).toInt()
                    if (normalized) (v / 127f).coerceAtLeast(-1f) else v.toFloat()
                }
                else -> error("Vertexformaat niet ondersteund: $componentType")
            }
        }

        fun addMesh(meshIndex: Int, world: FloatArray) {
            require(meshIndex in 0 until meshes.length()) { "Ongeldige meshverwijzing" }
            val primitives = meshes.getJSONObject(meshIndex).getJSONArray("primitives")
            for (pi in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(pi)
                if (primitive.optInt("mode", 4) != 4) continue
                val attributes = primitive.getJSONObject("attributes")
                val positionAccessor = accessors.getJSONObject(attributes.getInt("POSITION"))
                val positionView = views.getJSONObject(positionAccessor.getInt("bufferView"))
                val vertexCount = positionAccessor.getInt("count")
                val componentType = positionAccessor.getInt("componentType")
                val componentBytes = when (componentType) { 5126, 5125 -> 4; 5123, 5122 -> 2; 5121, 5120 -> 1; else -> error("Vertexformaat niet ondersteund: $componentType") }
                val stride = positionView.optInt("byteStride", componentBytes * 3)
                val baseOffset = positionView.optInt("byteOffset", 0) + positionAccessor.optInt("byteOffset", 0)
                val normalized = positionAccessor.optBoolean("normalized", false)
                val rawPositions = Array(vertexCount) { index ->
                    val p = baseOffset + index * stride
                    floatArrayOf(
                        readComponent(binary, p, componentType, normalized),
                        readComponent(binary, p + componentBytes, componentType, normalized),
                        readComponent(binary, p + componentBytes * 2, componentType, normalized)
                    )
                }
                fun readAttribute(attributeName: String, componentCount: Int): Array<FloatArray>? {
                    val accessorIndex = attributes.optInt(attributeName, -1)
                    if (accessorIndex < 0) return null
                    val accessor = accessors.getJSONObject(accessorIndex)
                    val bufferView = views.getJSONObject(accessor.getInt("bufferView"))
                    val type = accessor.getInt("componentType")
                    val bytesPerComponent = when (type) { 5126, 5125 -> 4; 5123, 5122 -> 2; 5121, 5120 -> 1; else -> error("Attribuutformaat niet ondersteund: $type") }
                    val attributeStride = bufferView.optInt("byteStride", bytesPerComponent * componentCount)
                    val first = bufferView.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
                    val isNormalized = accessor.optBoolean("normalized", false)
                    require(accessor.getInt("count") == vertexCount) { "Attribuut $attributeName heeft een afwijkend aantal vertices" }
                    return Array(vertexCount) { index ->
                        val p = first + index * attributeStride
                        FloatArray(componentCount) { component -> readComponent(binary, p + component * bytesPerComponent, type, isNormalized) }
                    }
                }
                val rawNormals = readAttribute("NORMAL", 3)
                val rawUvs = readAttribute("TEXCOORD_0", 2)
                val indexList = mutableListOf<Int>()
                if (primitive.has("indices")) {
                    val accessor = accessors.getJSONObject(primitive.getInt("indices"))
                    val indexView = views.getJSONObject(accessor.getInt("bufferView"))
                    val type = accessor.getInt("componentType")
                    val bytesPerIndex = when (type) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Indexformaat niet ondersteund: $type") }
                    val first = indexView.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
                    repeat(accessor.getInt("count")) { i ->
                        val p = first + i * bytesPerIndex
                        val value = when (type) {
                            5121 -> binary.get(p).toInt() and 0xff
                            5123 -> binary.getShort(p).toInt() and 0xffff
                            else -> binary.getInt(p).toLong().and(0xffffffffL).toInt()
                        }
                        require(value in rawPositions.indices) { "Mesh-index buiten bereik" }
                        indexList.add(value)
                    }
                } else {
                    repeat(vertexCount) { indexList.add(it) }
                }
                require(indexList.size % 3 == 0) { "Mesh bevat geen volledige driehoeken" }
                // Expand indexed triangles. This avoids the 16-bit index limit on OpenGL ES 2.
                val transformed = Array(indexList.size) { j ->
                    val v = rawPositions[indexList[j]]; val x=v[0]; val y=v[1]; val z=v[2]
                    floatArrayOf(world[0]*x+world[4]*y+world[8]*z+world[12], world[1]*x+world[5]*y+world[9]*z+world[13], world[2]*x+world[6]*y+world[10]*z+world[14])
                }
                val vertexData = ByteBuffer.allocateDirect(indexList.size * 3 * 4).order(ByteOrder.nativeOrder())
                val normalData = ByteBuffer.allocateDirect(indexList.size * 3 * 4).order(ByteOrder.nativeOrder())
                val uvData = if (rawUvs != null) ByteBuffer.allocateDirect(indexList.size * 2 * 4).order(ByteOrder.nativeOrder()) else null
                for (i in transformed.indices step 3) {
                    val a=transformed[i]; val b=transformed[i+1]; val c=transformed[i+2]
                    val ux=b[0]-a[0]; val uy=b[1]-a[1]; val uz=b[2]-a[2]
                    val vx=c[0]-a[0]; val vy=c[1]-a[1]; val vz=c[2]-a[2]
                    var fx=uy*vz-uz*vy; var fy=uz*vx-ux*vz; var fz=ux*vy-uy*vx
                    val flen=kotlin.math.sqrt(fx*fx+fy*fy+fz*fz).coerceAtLeast(0.000001f)
                    fx/=flen; fy/=flen; fz/=flen
                    for(j in 0..2) {
                        val vertexIndex=indexList[i+j]; val p=transformed[i+j]
                        vertexData.putFloat(p[0]); vertexData.putFloat(p[1]); vertexData.putFloat(p[2])
                        val sourceNormal=rawNormals?.get(vertexIndex)
                        var nx:Float; var ny:Float; var nz:Float
                        if (sourceNormal != null) {
                            nx=world[0]*sourceNormal[0]+world[4]*sourceNormal[1]+world[8]*sourceNormal[2]
                            ny=world[1]*sourceNormal[0]+world[5]*sourceNormal[1]+world[9]*sourceNormal[2]
                            nz=world[2]*sourceNormal[0]+world[6]*sourceNormal[1]+world[10]*sourceNormal[2]
                            val nlen=kotlin.math.sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(0.000001f)
                            nx/=nlen; ny/=nlen; nz/=nlen
                        } else { nx=fx; ny=fy; nz=fz }
                        normalData.putFloat(nx); normalData.putFloat(ny); normalData.putFloat(nz)
                        if (uvData != null) {
                            val tex=rawUvs!![vertexIndex]
                            uvData.putFloat(tex[0]); uvData.putFloat(1f-tex[1])
                        }
                    }
                }
                vertexData.flip(); normalData.flip(); uvData?.flip()
                val mi=primitive.optInt("material",-1)
                val material=if(materials!=null && mi in 0 until materials.length()) materials.getJSONObject(mi) else null
                val pbr=material?.optJSONObject("pbrMetallicRoughness")
                val factor=pbr?.optJSONArray("baseColorFactor")
                val color=if(factor!=null && factor.length()>=4) FloatArray(4){k->factor.optDouble(k,1.0).toFloat()} else floatArrayOf(1f,1f,1f,1f)
                val textureRef=pbr?.optJSONObject("baseColorTexture")?.optInt("index",-1) ?: -1
                val imageIndex=if(textures!=null && textureRef in 0 until textures.length()) textures.getJSONObject(textureRef).optInt("source",-1) else -1
                val alphaMode=material?.optString("alphaMode","OPAQUE") ?: "OPAQUE"
                val doubleSided=material?.optBoolean("doubleSided",false) ?: false
                parts.add(Part(vertexData.asFloatBuffer(), null, indexList.size, color, normalData.asFloatBuffer(), uvData?.asFloatBuffer(), imageIndex.takeIf { it >= 0 }, alphaMode == "BLEND" || color[3] < 0.999f, doubleSided))
            }
        }

        if (nodes != null && nodes.length() > 0) {
            val sceneIndex = root.optInt("scene", 0)
            val scenes = root.optJSONArray("scenes")
            val roots = if (scenes != null && sceneIndex in 0 until scenes.length()) {
                scenes.getJSONObject(sceneIndex).optJSONArray("nodes")
            } else null
            val visited = mutableSetOf<Int>()
            fun visit(nodeIndex: Int, parent: FloatArray) {
                require(nodeIndex in 0 until nodes.length()) { "Ongeldige nodeverwijzing" }
                // A node may be instanced more than once; traverse each occurrence.
                val node = nodes.getJSONObject(nodeIndex)
                val local = nodeMatrix(node)
                val world = FloatArray(16)
                Matrix.multiplyMM(world, 0, parent, 0, local, 0)
                if (node.has("mesh")) addMesh(node.getInt("mesh"), world)
                val children = node.optJSONArray("children")
                if (children != null) for (i in 0 until children.length()) visit(children.getInt(i), world)
            }
            if (roots != null && roots.length() > 0) {
                val identity = FloatArray(16)
                Matrix.setIdentityM(identity, 0)
                for (i in 0 until roots.length()) visit(roots.getInt(i), identity)
            } else {
                for (i in 0 until meshes.length()) addMesh(i, FloatArray(16).also { Matrix.setIdentityM(it, 0) })
            }
        } else {
            val identity = FloatArray(16)
            Matrix.setIdentityM(identity, 0)
            for (i in 0 until meshes.length()) addMesh(i, identity)
        }
        require(parts.isNotEmpty()) { "GLB bevat geen driehoeksmeshes" }
        return parts
    }

    private fun floats(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER,vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER,fs)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it,v); GLES20.glAttachShader(it,f); GLES20.glLinkProgram(it)
            val status = IntArray(1); GLES20.glGetProgramiv(it,GLES20.GL_LINK_STATUS,status,0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(it) }
        }
    }
    private fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it,source); GLES20.glCompileShader(it)
        val status = IntArray(1); GLES20.glGetShaderiv(it,GLES20.GL_COMPILE_STATUS,status,0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(it) }
    }

    companion object {
        private const val CAMERA_VS = "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=aPos;vUv=aUv;}"
        private const val CAMERA_FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES sCamera; varying vec2 vUv; void main(){gl_FragColor=texture2D(sCamera,vUv);}"
        private const val MODEL_VS = "attribute vec4 aPos; attribute vec3 aNormal; attribute vec2 aUv; uniform mat4 uMvp; varying vec3 vNormal; varying vec2 vUv; void main(){gl_Position=uMvp*aPos;vNormal=aNormal;vUv=aUv;}"
        private const val MODEL_FS = "precision mediump float; uniform vec4 uColor; uniform sampler2D uTexture; uniform int uUseTexture; varying vec3 vNormal; varying vec2 vUv; void main(){vec4 base=uColor;if(uUseTexture==1)base*=texture2D(uTexture,vUv);vec3 n=normalize(vNormal);vec3 l1=normalize(vec3(-0.45,0.82,0.36));vec3 l2=normalize(vec3(0.45,0.20,-0.70));float d=max(dot(n,l1),0.0);float fill=max(dot(n,l2),0.0);float light=0.48+0.40*d+0.12*fill;gl_FragColor=vec4(base.rgb*light,base.a);}"
    }
}
