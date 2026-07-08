package com.scorecast.app.overlay

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.scorecast.app.StreamConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The Phase 1 burn-in engine.
 *
 * StreamPack 3.x has no overlay API: the encoder/processor consumes a single video source via a
 * Surface, and [io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessor]
 * is `private`/internal so it can't be subclassed. The supported seam is a custom video source — so
 * this engine does the compositing itself:
 *
 *   Camera2 (StreamPack CameraSource) --> cameraInputSurface (external-OES SurfaceTexture)
 *        --> [this GL renderer: draw camera quad, then alpha-blend the overlay quad]
 *        --> outputSurface (StreamPack's encoder/processor input)  ==> H.264 ==> RTMPS
 *        --> previewSurface (operator's on-screen SurfaceView, optional)
 *
 * Everything runs on one dedicated GL thread that owns the EGL context.
 *
 * NOTE (honest status): the math here is standard external-OES compositing, but it has NOT been run
 * on a GPU/camera from this environment. Orientation/mirroring is the usual device-specific snag —
 * tune via [StreamConfig.CAMERA_FLIP_VERTICAL]/[StreamConfig.CAMERA_FLIP_HORIZONTAL]. See README.
 */
class OverlayCompositor(
    private var outputSurface: Surface?,
    private val size: Size,
    private val overlayBitmap: Bitmap?,
    flipHorizontal: Boolean = StreamConfig.CAMERA_FLIP_HORIZONTAL,
    flipVertical: Boolean = StreamConfig.CAMERA_FLIP_VERTICAL,
    rotationDegrees: Int = StreamConfig.CAMERA_ROTATION_DEGREES,
) {
    private val thread = HandlerThread("scorecast-gl").apply { start() }
    private val handler = Handler(thread.looper)

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    // True while outputEglSurface is a throwaway 1x1 PBuffer rather than the real encoder Surface —
    // lets the camera/GL pipeline warm up (and keep draining SurfaceTexture frames) before the real
    // output Surface exists, without presenting anything anywhere.
    private var usingPlaceholderOutput = false
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth = 0
    private var previewHeight = 0

    // Camera input
    private var cameraTexId = 0
    private lateinit var cameraSurfaceTexture: SurfaceTexture
    lateinit var cameraInputSurface: Surface
        private set

    // Overlay
    private var overlayTexId = 0
    private var overlayReady = false

    // GL programs
    private var oesProgram = 0
    private var oesPosLoc = 0
    private var oesTexLoc = 0
    private var oesMatrixLoc = 0
    private var twoDProgram = 0
    private var twoDPosLoc = 0
    private var twoDTexLoc = 0

    private val texMatrix = FloatArray(16)

    // Camera texture coords; rebuilt on the GL thread by [setTransform] for live orientation tuning.
    private val fullQuadPos = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private var cameraTexCoords = floatBuffer(cameraTexArray(flipHorizontal, flipVertical, rotationDegrees))
    // Full-frame NDC quad: overlay bitmap covers the entire frame, positioning is done in Canvas space.
    private val overlayPosBuf: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val overlayTexCoords = floatBuffer(
        // V flipped: bitmaps are top-left origin, GL is bottom-left.
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    )

    @Volatile private var released = false

    /** Initialises EGL/GL and the camera input surface. Blocks until ready. */
    fun start() {
        runBlockingOnGl {
            initEgl()
            makeCurrent(ensureOutputEglSurface())
            initPrograms()
            initCameraInput()
            initOverlay()
        }
    }

    /**
     * Redirects the encoder output to a new Surface without touching the camera input or capture
     * session. StreamPack can replace the encoder's input surface after the fact (e.g. falling back
     * to a color format the hardware encoder actually supports) — when that happens we must follow
     * the new surface here rather than re-touch the camera, since some HALs (Legacy-tier Camera2 in
     * particular) reject reconfiguring an already-active capture session's target surface.
     */
    fun setOutputSurface(surface: Surface) {
        handler.post {
            if (released) return@post
            if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
                outputEglSurface = EGL14.EGL_NO_SURFACE
            }
            outputSurface = surface
            usingPlaceholderOutput = false
            ensureOutputEglSurface()
        }
    }

    /** Operator preview surface; may be set, changed, or cleared at any time. */
    fun setPreviewSurface(surface: Surface?) {
        handler.post {
            if (released) return@post
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid) {
                previewEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
                // Cache the preview window's actual pixel size so we render into all of it,
                // not the stream resolution (which would clip the composite to a corner).
                val dims = IntArray(1)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, dims, 0)
                previewWidth = dims[0]
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, dims, 0)
                previewHeight = dims[0]
            } else {
                previewWidth = 0
                previewHeight = 0
            }
        }
    }

    /**
     * Live orientation tuning (see README "Bring-up order"). Rebuilds the camera texture
     * coordinates on the GL thread so the operator can correct rotation/mirroring on-device
     * without recompiling. Takes effect on the next frame.
     */
    fun setTransform(horizontal: Boolean, vertical: Boolean, rotationDegrees: Int) {
        handler.post {
            if (released) return@post
            cameraTexCoords = floatBuffer(cameraTexArray(horizontal, vertical, rotationDegrees))
        }
    }

    fun release() {
        if (released) return
        released = true
        runBlockingOnGl {
            if (::cameraSurfaceTexture.isInitialized) {
                cameraSurfaceTexture.setOnFrameAvailableListener(null)
                cameraSurfaceTexture.release()
            }
            if (::cameraInputSurface.isInitialized) cameraInputSurface.release()
            if (overlayTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(overlayTexId), 0)
            if (cameraTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(cameraTexId), 0)
            if (outputEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglTerminate(eglDisplay)
            }
        }
        thread.quitSafely()
    }

    // ---- frame loop (GL thread) ----

    private val onFrame = SurfaceTexture.OnFrameAvailableListener {
        if (released) return@OnFrameAvailableListener
        handler.post { drawFrame() }
    }

    private fun drawFrame() {
        if (released) return
        try {
            // 1) Encoder/processor output — carries the camera frame timestamp.
            //    Make the context current BEFORE updateTexImage (it binds to the current context).
            //    updateTexImage() must run every frame regardless of placeholder state, or the
            //    camera's SurfaceTexture buffer queue fills up and stalls the capture session —
            //    this is what lets the camera warm up before the real encoder surface exists.
            makeCurrent(outputEglSurface)
            cameraSurfaceTexture.updateTexImage()
            cameraSurfaceTexture.getTransformMatrix(texMatrix)
            if (!usingPlaceholderOutput) {
                renderComposite(size.width, size.height)
                EGLExt.eglPresentationTimeANDROID(
                    eglDisplay, outputEglSurface, cameraSurfaceTexture.timestamp
                )
                EGL14.eglSwapBuffers(eglDisplay, outputEglSurface)
            }

            // 2) Operator preview (best-effort, not encoded) — fill the preview window.
            if (previewEglSurface != EGL14.EGL_NO_SURFACE && previewWidth > 0 && previewHeight > 0) {
                makeCurrent(previewEglSurface)
                renderComposite(previewWidth, previewHeight)
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame failed", t)
        }
    }

    private fun renderComposite(viewportWidth: Int, viewportHeight: Int) {
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Camera (external OES) full-frame.
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniformMatrix4fv(oesMatrixLoc, 1, false, texMatrix, 0)
        vertexAttrib(oesPosLoc, fullQuadPos, 2)
        vertexAttrib(oesTexLoc, cameraTexCoords, 2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Overlay (2D, alpha-blended) — the burned-in scoreboard.
        if (StreamConfig.USE_OVERLAY && overlayReady) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(twoDProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
            vertexAttrib(twoDPosLoc, overlayPosBuf, 2)
            vertexAttrib(twoDTexLoc, overlayTexCoords, 2)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    // ---- setup (GL thread) ----

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Must support PBuffer, not just Window: warm-up uses a throwaway PBuffer surface
            // before the real encoder Surface exists (EGL defaults to EGL_WINDOW_BIT only if
            // EGL_SURFACE_TYPE isn't listed explicitly).
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    private fun ensureOutputEglSurface(): EGLSurface {
        if (outputEglSurface == EGL14.EGL_NO_SURFACE) {
            val surface = outputSurface
            outputEglSurface = if (surface != null) {
                usingPlaceholderOutput = false
                EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
            } else {
                // No real encoder surface yet (camera warm-up path) — a throwaway 1x1 PBuffer
                // lets EGL/GL and the camera's SurfaceTexture stand up and start draining frames
                // now; drawFrame() skips presenting until setOutputSurface() swaps in the real one.
                usingPlaceholderOutput = true
                EGL14.eglCreatePbufferSurface(
                    eglDisplay, eglConfig,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
                )
            }
        }
        return outputEglSurface
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun initCameraInput() {
        cameraTexId = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        cameraSurfaceTexture = SurfaceTexture(cameraTexId).apply {
            setDefaultBufferSize(size.width, size.height)
            setOnFrameAvailableListener(onFrame, handler)
        }
        cameraInputSurface = Surface(cameraSurfaceTexture)
    }

    private fun initOverlay() {
        val bmp = overlayBitmap ?: return
        overlayTexId = genTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        overlayReady = true
    }

    /** Replace the overlay bitmap live. Bitmap must be full-frame size. Safe to call from any thread. */
    fun updateOverlay(bitmap: Bitmap) {
        handler.post {
            if (released) return@post
            if (overlayTexId == 0) overlayTexId = genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            overlayReady = true
        }
    }

    private fun initPrograms() {
        oesProgram = buildProgram(VERTEX_SHADER, OES_FRAGMENT_SHADER)
        oesPosLoc = GLES20.glGetAttribLocation(oesProgram, "aPos")
        oesTexLoc = GLES20.glGetAttribLocation(oesProgram, "aTex")
        oesMatrixLoc = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        check(oesPosLoc >= 0) { "OES shader: 'aPos' not found (loc=$oesPosLoc)" }
        check(oesTexLoc >= 0) { "OES shader: 'aTex' not found (loc=$oesTexLoc)" }
        check(oesMatrixLoc >= 0) { "OES shader: 'uTexMatrix' not found (loc=$oesMatrixLoc)" }

        twoDProgram = buildProgram(VERTEX_2D_SHADER, TWOD_FRAGMENT_SHADER)
        twoDPosLoc = GLES20.glGetAttribLocation(twoDProgram, "aPos")
        twoDTexLoc = GLES20.glGetAttribLocation(twoDProgram, "aTex")
        check(twoDPosLoc >= 0) { "2D shader: 'aPos' not found (loc=$twoDPosLoc)" }
        check(twoDTexLoc >= 0) { "2D shader: 'aTex' not found (loc=$twoDTexLoc)" }
    }

    private fun cameraTexArray(
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        rotationDegrees: Int,
    ): FloatArray {
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        if (flipHorizontal) { val t = u0; u0 = u1; u1 = t }
        if (flipVertical) { val t = v0; v0 = v1; v1 = t }
        // Matches fullQuadPos order BL, BR, TL, TR
        val uv = floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)

        val r = ((rotationDegrees % 360) + 360) % 360
        if (r == 0) return uv
        // Rotate each (u,v) clockwise about the texture centre (0.5, 0.5).
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val u = uv[i * 2]
            val v = uv[i * 2 + 1]
            when (r) {
                90 -> { out[i * 2] = v;        out[i * 2 + 1] = 1f - u }
                180 -> { out[i * 2] = 1f - u;  out[i * 2 + 1] = 1f - v }
                270 -> { out[i * 2] = 1f - v;  out[i * 2 + 1] = u }
                else -> { out[i * 2] = u;      out[i * 2 + 1] = v }
            }
        }
        return out
    }

    private fun runBlockingOnGl(block: () -> Unit) {
        if (Thread.currentThread() == thread) { block(); return }
        val lock = Object()
        var done = false
        var err: Throwable? = null
        handler.post {
            try { block() } catch (t: Throwable) { err = t }
            synchronized(lock) { done = true; lock.notifyAll() }
        }
        synchronized(lock) { while (!done) lock.wait() }
        err?.let { throw it }
    }

    companion object {
        private const val TAG = "OverlayCompositor"

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """

        private const val VERTEX_2D_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex; }
        """

        private const val TWOD_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }

        private fun vertexAttrib(loc: Int, buf: FloatBuffer, comps: Int) {
            buf.position(0)
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, comps, GLES20.GL_FLOAT, false, 0, buf)
        }

        private fun genTexture(target: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(target, 0)
            return ids[0]
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Link failed: ${GLES20.glGetProgramInfoLog(p)}" }
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
    }
}
