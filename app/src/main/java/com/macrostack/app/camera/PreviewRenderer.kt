package com.macrostack.app.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the camera preview with OpenGL into a TextureView, with focus peaking computed in the same
 * pass on the GPU. The highlights belong to exactly the frame on screen (no lag behind the image) and
 * are measured at the preview's full resolution. The test is the one in [Peaking].
 *
 * The camera renders into [cameraSurface]; everything else happens on a private GL thread. If OpenGL
 * can't be set up, the camera draws straight into the view instead, without peaking.
 */
class PreviewRenderer(private val output: SurfaceTexture, width: Int, height: Int) {

    data class PeakingSettings(val enabled: Boolean, val color: Int, val sharpness: Float, val minContrast: Float)

    @Volatile
    var peaking = PeakingSettings(true, Peaking.COLORS[0], Peaking.SHARPNESS[1], Peaking.MIN_CONTRAST[1])

    private val thread = HandlerThread("preview-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val surfaceReady = CompletableDeferred<Surface>()

    @Volatile
    private var viewWidth = width

    @Volatile
    private var viewHeight = height

    @Volatile
    private var bufferWidth = 1

    @Volatile
    private var bufferHeight = 1

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var directSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private var released = false

    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var uTexel = 0
    private var uTexSize = 0
    private var uPeaking = 0
    private var uColor = 0
    private var uSharpness = 0
    private var uMinContrast = 0

    /** Full-screen quad: x, y, s, t per corner, drawn as a triangle strip. */
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        handler.post { setUp() }
    }

    /** False when OpenGL couldn't be set up, so there is no peaking. Known once [cameraSurface] returns. */
    @Volatile
    var peakingAvailable = true
        private set

    /**
     * The surface the camera should draw the preview into, sized for a [width] × [height] stream
     * (sensor orientation). Waits for the GL setup if it's still running.
     */
    suspend fun cameraSurface(width: Int, height: Int): Surface {
        bufferWidth = width
        bufferHeight = height
        val surface = try {
            surfaceReady.await()
        } catch (e: Exception) {
            // No OpenGL: let the camera draw into the view directly (the GL surface on it is gone).
            peakingAvailable = false
            output.setDefaultBufferSize(width, height)
            return directSurface ?: Surface(output).also { directSurface = it }
        }
        cameraTexture?.setDefaultBufferSize(width, height)
        return surface
    }

    fun resize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    /** Tears everything down, including the TextureView's SurfaceTexture. */
    fun release() {
        handler.post {
            released = true
            tearDownGl()
            directSurface?.release()
            output.release()
            thread.quitSafely()
        }
    }

    private fun tearDownGl() {
        try {
            cameraSurface?.release()
            cameraTexture?.release()
            cameraSurface = null
            cameraTexture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Releasing OpenGL", e)
        }
        program = 0
        texture = 0
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun setUp() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

            program = buildProgram()
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uTexel = GLES20.glGetUniformLocation(program, "uTexel")
            uTexSize = GLES20.glGetUniformLocation(program, "uTexSize")
            uPeaking = GLES20.glGetUniformLocation(program, "uPeaking")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            uSharpness = GLES20.glGetUniformLocation(program, "uSharpness")
            uMinContrast = GLES20.glGetUniformLocation(program, "uMinContrast")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(texture)
            st.setOnFrameAvailableListener({ drawFrame() }, handler)
            cameraTexture = st
            val surface = Surface(st)
            cameraSurface = surface
            surfaceReady.complete(surface)
        } catch (e: Exception) {
            Log.e(TAG, "OpenGL preview setup failed; showing the camera without peaking", e)
            tearDownGl()
            surfaceReady.completeExceptionally(e)
        }
    }

    private fun drawFrame() {
        if (released) return
        val st = cameraTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uTexel, 1f / bufferWidth, 1f / bufferHeight)
            GLES20.glUniform2f(uTexSize, bufferWidth.toFloat(), bufferHeight.toFloat())
            val p = peaking
            GLES20.glUniform1f(uPeaking, if (p.enabled) 1f else 0f)
            GLES20.glUniform3f(
                uColor,
                ((p.color shr 16) and 0xFF) / 255f,
                ((p.color shr 8) and 0xFF) / 255f,
                (p.color and 0xFF) / 255f,
            )
            GLES20.glUniform1f(uSharpness, p.sharpness)
            GLES20.glUniform1f(uMinContrast, p.minContrast)

            quad.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPosition)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Preview frame failed", e)
        }
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private companion object {
        const val TAG = "PreviewRenderer"

        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        // Peaking — the same test as Peaking.isSharp: Sobel steepness against the 7×7 brightness
        // range, measured on exact camera pixels (the sample point is snapped to a pixel centre).
        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexel;
            uniform vec2 uTexSize;
            uniform float uPeaking;
            uniform vec3 uColor;
            uniform float uSharpness;
            uniform float uMinContrast;
            varying vec2 vTex;

            float luma(vec2 tc) {
                return dot(texture2D(uTexture, tc).rgb, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec4 color = texture2D(uTexture, vTex);
                if (uPeaking > 0.5) {
                    vec2 c = (floor(vTex * uTexSize) + 0.5) / uTexSize;
                    float lo = 1.0;
                    float hi = 0.0;
                    float gx = 0.0;
                    float gy = 0.0;
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            float v = luma(c + vec2(float(dx), float(dy)) * uTexel);
                            lo = min(lo, v);
                            hi = max(hi, v);
                            // Sobel, from the inner 3×3 of the same reads (weights 1-2-1).
                            if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1) {
                                float k = (dx == 0 || dy == 0) ? 2.0 : 1.0;
                                gx += float(dx) * k * v;
                                gy += float(dy) * k * v;
                            }
                        }
                    }
                    gx /= 8.0;
                    gy /= 8.0;
                    float range = hi - lo;
                    if (range > uMinContrast && length(vec2(gx, gy)) > uSharpness * range) {
                        color.rgb = uColor;
                    }
                }
                gl_FragColor = color;
            }
        """.trimIndent()
    }
}
