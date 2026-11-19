package nl.leeuw008.tijdbeeld

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
class OmmerschansModelRenderer(private val context: Context, private val status: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var session: Session? = null
    @Volatile private var lastStatus = ""
    @Volatile private var modelData: List<Part>? = null
    @Volatile private var loadStarted = false
    @Volatile private var anchor: Anchor? = null
    private var anchorRequested = false
    private var cameraTexture = 0
    private var cameraProgram = 0
    private var modelProgram = 0
    private var cameraPos = 0
    private var cameraUv = 0
    private var cameraSampler = 0
    private var modelPos = 0
    private var modelMvp = 0
    private var modelColor = 0
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

    data class Part(val vertices: FloatBuffer, val indices: ShortBuffer?, val count: Int, val color: FloatArray)

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
        modelMvp = GLES20.glGetUniformLocation(modelProgram, "uMvp")
        modelColor = GLES20.glGetUniformLocation(modelProgram, "uColor")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        loadModel()
    }

    fun setDisplayRotation(rotation: Int) { displayRotation = rotation; geometryChanged = true }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0,0,w,h)
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
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
                // GLB origin: courtyard centre at WGS84 52.58737559898257, 6.391727308884152.
                // Axes in the delivered model are +X east, +Y up and -Z true north.
                // ARCore Geospatial uses east/up/south, so identity rotation aligns the axes;
                // the building's 4.4-degree orientation is already present in the model geometry.
                earth.resolveAnchorOnTerrainAsync(
                    52.58737559898257, 6.391727308884152, 0.0,
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
            for (part in parts) {
                part.vertices.position(0)
                GLES20.glEnableVertexAttribArray(modelPos)
                GLES20.glVertexAttribPointer(modelPos,3,GLES20.GL_FLOAT,false,0,part.vertices)
                GLES20.glUniform4fv(modelColor,1,part.color,0)
                if (part.indices != null) {
                    part.indices.position(0)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES,part.count,GLES20.GL_UNSIGNED_SHORT,part.indices)
                } else {
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,part.count)
                }
                GLES20.glDisableVertexAttribArray(modelPos)
            }
            GLES20.glDisable(GLES20.GL_CULL_FACE)
        } catch (e: Exception) {
            report("3D-camera fout: ${e.message ?: e.javaClass.simpleName}")
        }
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
                val vertexData = ByteBuffer.allocateDirect(indexList.size * 3 * 4).order(ByteOrder.nativeOrder())
                for (index in indexList) {
                    val v = rawPositions[index]
                    val x = v[0]; val y = v[1]; val z = v[2]
                    vertexData.putFloat(world[0]*x + world[4]*y + world[8]*z + world[12])
                    vertexData.putFloat(world[1]*x + world[5]*y + world[9]*z + world[13])
                    vertexData.putFloat(world[2]*x + world[6]*y + world[10]*z + world[14])
                }
                vertexData.flip()
                parts.add(Part(vertexData.asFloatBuffer(), null, indexList.size, floatArrayOf(0.72f, 0.70f, 0.65f, 1f)))
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
        private const val MODEL_VS = "attribute vec4 aPos; uniform mat4 uMvp; void main(){gl_Position=uMvp*aPos;}"
        private const val MODEL_FS = "precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}"
    }
}
