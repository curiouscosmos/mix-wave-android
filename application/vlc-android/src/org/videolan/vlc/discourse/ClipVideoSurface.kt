package org.videolan.vlc.discourse

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/** Recordable EGL window shared by the decoder texture and H.264 encoder. */
internal class ClipVideoSurface(
    private val encoderSurface: Surface, photo: Bitmap?, private val width: Int, private val height: Int,
    sourceWidth: Int, sourceHeight: Int, endCard: Bitmap
) : Closeable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var endCardProgram = 0
    private var endCardTexture = 0
    private val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val endCardVertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }
    private var surfaceTexture: SurfaceTexture? = null
    var decoderSurface: Surface? = null
        private set
    private var callbackThread: HandlerThread? = null
    private val frameLock = Object()
    private var available = false
    private val transform = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val target = if (photo == null) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // Bitmap rows start at the top; SurfaceTexture supplies the decoder's vertical transform.
        val bottom = if (photo == null) 0f else 1f
        val top = 1f - bottom
        put(floatArrayOf(-1f, -1f, 0f, bottom, 1f, -1f, 1f, bottom,
            -1f, 1f, 0f, top, 1f, 1f, 1f, top))
        position(0)
    }
    private val viewport: IntArray

    init {
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val w = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val h = (sourceHeight * scale).toInt().coerceAtLeast(1)
        viewport = intArrayOf((width - w) / 2, (height - h) / 2, w, h)
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            check(EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142, 1, EGL14.EGL_NONE // EGL_RECORDABLE_ANDROID
            ), 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            window = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, window, window, context))
            val vertex = shader(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 position;
                attribute vec2 uv;
                uniform mat4 transform;
                varying vec2 texCoord;
                void main() {
                    gl_Position = vec4(position, 0.0, 1.0);
                    texCoord = (transform * vec4(uv, 0.0, 1.0)).xy;
                }
            """.trimIndent())
            val fragment = try {
                shader(GLES20.GL_FRAGMENT_SHADER, (if (photo == null) "#extension GL_OES_EGL_image_external : require\n" else "") + """
                    precision mediump float;
                    varying vec2 texCoord;
                    uniform ${if (photo == null) "samplerExternalOES" else "sampler2D"} image;
                    void main() { gl_FragColor = texture2D(image, texCoord); }
                """.trimIndent())
            } catch (error: Throwable) { GLES20.glDeleteShader(vertex); throw error }
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val endFragment = shader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                varying vec2 texCoord;
                uniform sampler2D image;
                uniform float opacity;
                void main() { gl_FragColor = vec4(texture2D(image, texCoord).rgb, opacity); }
            """.trimIndent())
            endCardProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(endCardProgram, vertex)
            GLES20.glAttachShader(endCardProgram, endFragment)
            GLES20.glLinkProgram(endCardProgram)
            GLES20.glDeleteShader(endFragment)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glGetProgramiv(endCardProgram, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(endCardProgram) }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            endCardTexture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, endCardTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, endCard, 0)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(target, texture)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            if (photo != null) GLUtils.texImage2D(target, 0, photo, 0)
            else {
                callbackThread = HandlerThread("clip-video-frames").apply { start() }
                surfaceTexture = SurfaceTexture(texture).apply {
                    setOnFrameAvailableListener({ synchronized(frameLock) { available = true; frameLock.notifyAll() } }, Handler(callbackThread!!.looper))
                }
                decoderSurface = Surface(surfaceTexture)
            }
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Cannot initialize video texture" }
        } catch (error: Throwable) { close(); throw error }
    }

    fun awaitFrame(coroutine: CoroutineContext) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        synchronized(frameLock) {
            while (!available) {
                coroutine.ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "Video surface stalled" }
                frameLock.wait(50)
            }
            available = false
        }
        surfaceTexture!!.updateTexImage()
        surfaceTexture!!.getTransformMatrix(transform)
    }

    fun draw(timeUs: Long, endCardOpacity: Float) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        drawTexture(program, target, texture, transform, vertices)
        if (endCardOpacity > 0f) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(endCardProgram)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(endCardProgram, "opacity"), endCardOpacity)
            drawTexture(endCardProgram, GLES20.GL_TEXTURE_2D, endCardTexture, identity, endCardVertices)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Video drawing failed" }
        check(EGLExt.eglPresentationTimeANDROID(display, window, timeUs * 1_000))
        check(EGL14.eglSwapBuffers(display, window)) { "Video surface swap failed" }
    }

    private fun drawTexture(program: Int, target: Int, texture: Int, transform: FloatArray, vertices: java.nio.FloatBuffer) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(message)
        }
        return shader
    }

    override fun close() {
        decoderSurface?.release(); decoderSurface = null
        surfaceTexture?.release(); surfaceTexture = null
        callbackThread?.quitSafely(); callbackThread = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (endCardProgram != 0) GLES20.glDeleteProgram(endCardProgram)
            if (endCardTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(endCardTexture), 0)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        encoderSurface.release()
    }
}
