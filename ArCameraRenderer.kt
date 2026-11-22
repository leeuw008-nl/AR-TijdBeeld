package nl.leeuw008.tijdbeeld

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.Pose
import com.google.ar.core.Session
import android.location.Location
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** ARCore camera background plus a first, locally anchored historical photo. */
class ArCameraRenderer : GLSurfaceView.Renderer {
    @Volatile private var session: Session? = null
    @Volatile private var imageBitmap: Bitmap? = null
    @Volatile private var imageLoadStarted = false
    @Volatile private var statusListener: ((String) -> Unit)? = null
    @Volatile private var directionListener: ((Float, Float) -> Unit)? = null
    private var lastDirectionUpdateNanos = 0L
    private var lastDirectionBearing: Float? = null
    private var lastDirectionDistance: Float? = null
    @Volatile private var lastReportedStatus = ""
    private var textureId = -1
    private var imageTextureId = 0
    private var cameraProgram = 0
    private var imageProgram = 0
    private var cameraPositionHandle = 0
    private var cameraUvHandle = 0
    private var cameraTextureHandle = 0
    private var imagePositionHandle = 0
    private var imageUvHandle = 0
    private var imageMvpHandle = 0
    private var imageSamplerHandle = 0
    private var imageAlphaHandle = 0
    @Volatile private var imageAlpha = 1f
    private var displayWidth = 0
    private var displayHeight = 0
    @Volatile private var displayRotation = android.view.Surface.ROTATION_0
    @Volatile private var geometryChanged = true
    private var textureSession: Session? = null
    @Volatile private var imageAnchor: Anchor? = null
    @Volatile private var targetLatitude = 0.0
    @Volatile private var targetLongitude = 0.0
    @Volatile private var targetAltitudeAboveTerrain = 1.65
    @Volatile private var targetQx = 0f
    @Volatile private var targetQy = 0f
    @Volatile private var targetQz = 0f
    @Volatile private var targetQw = 1f
    @Volatile private var targetRevision = 0
    private var requestedTargetRevision = -1
    @Volatile private var imageWidthMeters = 3f
    @Volatile private var imageHeightMeters = 0.8f
    @Volatile private var imageAspectRatio = 0.2667f
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val cameraVertices = floatBuffer(floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    ))
    private val baseUvs = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private val transformedUvs = floatBuffer(FloatArray(8))
    private val planeVertices = floatBuffer(floatArrayOf(
        -0.5f, -0.5f, 0f, 0f, 1f,
         0.5f, -0.5f, 0f, 1f, 1f,
        -0.5f,  0.5f, 0f, 0f, 0f,
         0.5f,  0.5f, 0f, 1f, 0f
    ))

    fun setStatusListener(listener: (String) -> Unit) {
        statusListener = listener
        if (lastReportedStatus.isNotBlank()) listener(lastReportedStatus)
    }

    fun reportStatus(message: String) {
        if (message == lastReportedStatus) return
        lastReportedStatus = message
        statusListener?.invoke(message)
    }

    fun setDirectionListener(listener: (Float, Float) -> Unit) {
        directionListener = listener
    }

    fun setSession(value: Session) {
        session = value
        textureSession = null
        geometryChanged = true
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
        geometryChanged = true
    }

    /** Sets the historical photo opacity from 0 (transparent) to 1 (opaque). */
    fun setImageAlpha(alpha: Float) {
        imageAlpha = alpha.coerceIn(0f, 1f)
    }

    /** Sets the physical width independently of the source image resolution. */
    fun setImageWidthMeters(width: Float) {
        imageWidthMeters = width.coerceIn(1f, 12f)
        imageHeightMeters = imageWidthMeters * imageAspectRatio
    }

    /** Stores a fixed WGS84 target and its world-facing orientation for a Terrain anchor. */
    fun setGeospatialTarget(
        latitude: Double, longitude: Double, altitudeAboveTerrain: Double,
        qx: Float, qy: Float, qz: Float, qw: Float
    ) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        targetLatitude = latitude
        targetLongitude = longitude
        targetAltitudeAboveTerrain = altitudeAboveTerrain
        targetQx = qx
        targetQy = qy
        targetQz = qz
        targetQw = qw
        targetRevision++
    }

    fun loadHistoricalImage(url: String, caption: String) {
        if (imageLoadStarted) return
        imageLoadStarted = true
        reportStatus("historische foto wordt geladen…")
        Thread {
            try {
                // Behoud de oorspronkelijke, eerder werkende laadwijze.
                // EXIF-oriëntatie wordt niet afzonderlijk gelezen of aangepast.
                val source = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                    ?: run {
                        reportStatus("foto kon niet worden gedecodeerd")
                        imageLoadStarted = false
                        return@Thread
                    }
                // Keep the complete source resolution; only the AR plane size is adjustable.
                // EXIF rotation changes pixel orientation, not image quality or source file.
                val targetWidth = source.width
                val targetHeight = source.height
                val captionHeight = (targetWidth * 0.11f).toInt().coerceAtLeast(112)
                val combined = Bitmap.createBitmap(targetWidth, targetHeight + captionHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(combined)
                canvas.drawBitmap(source, 0f, 0f, null)
                val textSize = targetWidth * 0.041f
                val x = targetWidth * 0.016f
                val baseline = targetHeight + targetWidth * 0.07f
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = targetWidth * 0.0068f
                    this.textSize = textSize
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    style = Paint.Style.FILL
                    this.textSize = textSize
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                canvas.drawText(caption, x, baseline, outline)
                canvas.drawText(caption, x, baseline, fill)
                // Provisional display width only; final physical size must be calibrated on site.
                // Keep this value easy to adjust after the first on-location test.
                imageAspectRatio = combined.height.toFloat() / combined.width.toFloat()
                imageHeightMeters = imageWidthMeters * imageAspectRatio
                imageBitmap = combined
                reportStatus("foto geladen (${source.width} × ${source.height})")
            } catch (exception: Exception) {
                imageLoadStarted = false
                reportStatus("foto laden mislukt: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }.start()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        cameraPositionHandle = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        cameraUvHandle = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
        cameraTextureHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")

        imageProgram = createProgram(IMAGE_VERTEX_SHADER, IMAGE_FRAGMENT_SHADER)
        imagePositionHandle = GLES20.glGetAttribLocation(imageProgram, "a_Position")
        imageUvHandle = GLES20.glGetAttribLocation(imageProgram, "a_TexCoord")
        imageMvpHandle = GLES20.glGetUniformLocation(imageProgram, "u_MVP")
        imageSamplerHandle = GLES20.glGetUniformLocation(imageProgram, "sTexture")
        imageAlphaHandle = GLES20.glGetUniformLocation(imageProgram, "u_Alpha")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        GLES20.glViewport(0, 0, width, height)
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return
        try {
            if (textureSession !== activeSession) {
                activeSession.setCameraTextureNames(intArrayOf(textureId))
                textureSession = activeSession
            }
            if (geometryChanged && displayWidth > 0 && displayHeight > 0) {
                activeSession.setDisplayGeometry(displayRotation, displayWidth, displayHeight)
                geometryChanged = false
            }
            val frame = activeSession.update()
            if (frame.camera.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                reportStatus("ARCore zoekt cameratracking…")
            }
            frame.transformDisplayUvCoords(baseUvs, transformedUvs)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(cameraProgram)
            cameraVertices.position(0)
            transformedUvs.position(0)
            GLES20.glEnableVertexAttribArray(cameraPositionHandle)
            GLES20.glVertexAttribPointer(cameraPositionHandle, 2, GLES20.GL_FLOAT, false, 0, cameraVertices)
            GLES20.glEnableVertexAttribArray(cameraUvHandle)
            GLES20.glVertexAttribPointer(cameraUvHandle, 2, GLES20.GL_FLOAT, false, 0, transformedUvs)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(cameraTextureHandle, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(cameraPositionHandle)
            GLES20.glDisableVertexAttribArray(cameraUvHandle)

            uploadImageIfNeeded()
            val bitmapReady = imageTextureId != 0
            if (bitmapReady && frame.camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                val earthForDirection = activeSession.earth
                if (earthForDirection != null &&
                    earthForDirection.earthState == Earth.EarthState.ENABLED &&
                    earthForDirection.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                    val directionPose = earthForDirection.cameraGeospatialPose
                    if (directionPose.latitude.isFinite() && directionPose.longitude.isFinite() && directionPose.heading.isFinite()) {
                        val currentLocation = Location("TijdBeeld-camera").apply {
                            latitude = directionPose.latitude
                            longitude = directionPose.longitude
                        }
                        val targetLocation = Location("TijdBeeld-fotolocatie").apply {
                            latitude = targetLatitude
                            longitude = targetLongitude
                        }
                        val relativeBearing = normalizeSignedBearing(
                            currentLocation.bearingTo(targetLocation) - directionPose.heading.toFloat()
                        )
                        val distance = currentLocation.distanceTo(targetLocation)
                        val now = System.nanoTime()
                        val bearingChanged = lastDirectionBearing == null ||
                            kotlin.math.abs(normalizeSignedBearing(relativeBearing - lastDirectionBearing!!)) >= 1f
                        val distanceChanged = lastDirectionDistance == null ||
                            kotlin.math.abs(distance - lastDirectionDistance!!) >= 1f
                        if (now - lastDirectionUpdateNanos >= 500_000_000L && (bearingChanged || distanceChanged)) {
                            lastDirectionUpdateNanos = now
                            lastDirectionBearing = relativeBearing
                            lastDirectionDistance = distance
                            directionListener?.invoke(relativeBearing, distance)
                        }
                    }
                }
                // Toon de historische foto uitsluitend na succesvolle geografische verankering.
                if (imageAnchor == null) {
                    val earth = activeSession.earth
                    if (earth == null) {
                        reportStatus("Geospatial-diagnose: Earth=null; ARCore heeft Earth nog niet beschikbaar")
                    } else if (earth.earthState != Earth.EarthState.ENABLED) {
                        reportStatus("Geospatial-diagnose: Earth=${earth.earthState}, tracking=${earth.trackingState}")
                    } else if (earth.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                        reportStatus("Geospatial-diagnose: Earth=ENABLED, tracking=${earth.trackingState}")
                    } else {
                        val geoPose = earth.cameraGeospatialPose
                        val horizontalAccuracy = geoPose.horizontalAccuracy.toInt()
                        val headingAccuracy = geoPose.headingAccuracy.toInt()
                        if (geoPose.horizontalAccuracy > 10.0 || geoPose.headingAccuracy > 15.0) {
                            reportStatus("Geospatial-diagnose: Earth=ENABLED, tracking=TRACKING, GPS-nauwkeurigheid=${horizontalAccuracy}m, kompas=${headingAccuracy}°")
                        } else if (requestedTargetRevision != targetRevision) {
                            requestedTargetRevision = targetRevision
                            reportStatus("foto zichtbaar; historische fotolocatie wordt vastgezet…")
                            try {
                                earth.resolveAnchorOnTerrainAsync(
                                    targetLatitude, targetLongitude, targetAltitudeAboveTerrain,
                                    targetQx, targetQy, targetQz, targetQw
                                ) { anchor, state ->
                                    if (state == Anchor.TerrainAnchorState.SUCCESS && anchor != null) {
                                        imageAnchor = anchor
                                        reportStatus("historische foto geografisch verankerd")
                                    } else {
                                        requestedTargetRevision = -1
                                        val detail = when (state) {
                                            Anchor.TerrainAnchorState.ERROR_NOT_AUTHORIZED -> "foto blijft zichtbaar; Google Cloud-autorisatie niet geaccepteerd"
                                            Anchor.TerrainAnchorState.ERROR_UNSUPPORTED_LOCATION -> "foto blijft zichtbaar; locatie wordt niet door VPS ondersteund"
                                            else -> "foto blijft zichtbaar; geografisch anker mislukt: $state"
                                        }
                                        reportStatus(detail)
                                    }
                                }
                            } catch (exception: Exception) {
                                requestedTargetRevision = -1
                                reportStatus("foto blijft zichtbaar; geografisch anker mislukt: " + (exception.message ?: exception.javaClass.simpleName))
                            }
                        }
                    }
                }
                val anchor = imageAnchor
                if (anchor != null && anchor.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                    frame.camera.getViewMatrix(viewMatrix, 0)
                    frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 1000f)
                    anchor.pose.toMatrix(modelMatrix, 0)
                    modelMatrix[0] *= imageWidthMeters
                    modelMatrix[1] *= imageWidthMeters
                    modelMatrix[2] *= imageWidthMeters
                    modelMatrix[4] *= imageHeightMeters
                    modelMatrix[5] *= imageHeightMeters
                    modelMatrix[6] *= imageHeightMeters
                    android.opengl.Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                    android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glUseProgram(imageProgram)
                    planeVertices.position(0)
                    GLES20.glEnableVertexAttribArray(imagePositionHandle)
                    GLES20.glVertexAttribPointer(imagePositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, planeVertices)
                    planeVertices.position(3)
                    GLES20.glEnableVertexAttribArray(imageUvHandle)
                    GLES20.glVertexAttribPointer(imageUvHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, planeVertices)
                    GLES20.glUniformMatrix4fv(imageMvpHandle, 1, false, mvpMatrix, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
                    GLES20.glUniform1i(imageSamplerHandle, 0)
                    GLES20.glUniform1f(imageAlphaHandle, imageAlpha)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(imagePositionHandle)
                    GLES20.glDisableVertexAttribArray(imageUvHandle)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }
            }
        } catch (exception: Exception) {
            reportStatus("AR-camera fout: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun normalizeSignedBearing(value: Float): Float {
        var normalized = (value + 180f) % 360f
        if (normalized < 0f) normalized += 360f
        return normalized - 180f
    }

    private fun uploadImageIfNeeded() {
        if (imageTextureId != 0) return
        val bitmap = imageBitmap ?: return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        imageTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        imageBitmap = null
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "OpenGL-programma kon niet worden gekoppeld." }
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        }

    companion object {
        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """
        private const val IMAGE_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            uniform mat4 u_MVP;
            varying vec2 v_TexCoord;
            void main() { gl_Position = u_MVP * a_Position; v_TexCoord = a_TexCoord; }
        """
        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            uniform float u_Alpha;
            varying vec2 v_TexCoord;
            void main() {
                vec4 color = texture2D(sTexture, v_TexCoord);
                gl_FragColor = vec4(color.rgb, color.a * u_Alpha);
            }
        """
    }
}
