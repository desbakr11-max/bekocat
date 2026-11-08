package app.bekocat.engine.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GlException(msg: String) : RuntimeException(msg)

fun glCheck(op: String) {
    val e = GLES30.glGetError()
    if (e != GLES30.GL_NO_ERROR) throw GlException("$op: GL error 0x${Integer.toHexString(e)}")
}

/** EGL display + GLES 3 context. Surfaces: window (preview / encoder input) or 1×1 pbuffer (offscreen). */
class EglCore(shared: EGLContext? = null, recordable: Boolean = true) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw GlException("No EGL display")
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw GlException("eglInitialize failed")
        val attribs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
        )
        if (recordable) { attribs += 0x3142; attribs += 1 } // EGL_RECORDABLE_ANDROID
        attribs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, num, 0) || num[0] == 0) {
            // Retry without the recordable bit (some emulators).
            val a2 = attribs.toMutableList().apply { val i = indexOf(0x3142); if (i >= 0) { removeAt(i); removeAt(i) } }
            if (!EGL14.eglChooseConfig(display, a2.toIntArray(), 0, configs, 0, 1, num, 0) || num[0] == 0) throw GlException("No EGL config")
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, shared ?: EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext failed")
    }

    fun windowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0).also { if (it == EGL14.EGL_NO_SURFACE) throw GlException("window surface failed") }

    fun pbuffer(w: Int = 1, h: Int = 1): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)

    fun makeCurrent(s: EGLSurface) { if (!EGL14.eglMakeCurrent(display, s, s, context)) throw GlException("eglMakeCurrent failed") }
    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)
    fun setPresentationTime(s: EGLSurface, nanos: Long) = EGLExt.eglPresentationTimeANDROID(display, s, nanos)
    fun releaseSurface(s: EGLSurface) = EGL14.eglDestroySurface(display, s)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}

class Program(vertex: String, fragment: String) {
    val id: Int
    private val locations = HashMap<String, Int>()
    private val samplerUnits = HashMap<String, Int>() // must precede init (filled during init)

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs); GLES30.glAttachShader(id, fs)
        GLES30.glBindAttribLocation(id, 0, "aPos")
        GLES30.glLinkProgram(id)
        val ok = IntArray(1); GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw GlException("link: " + GLES30.glGetProgramInfoLog(id))
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        assignSamplerUnits()
    }

    /**
     * Gives every sampler uniform its own texture unit for the program's lifetime. GLES 3 forbids
     * samplers of different types (2D / 3D / external) sharing a unit at draw time — unused samplers
     * all default to unit 0, which strict drivers reject with GL_INVALID_OPERATION.
     */
    private fun assignSamplerUnits() {
        val n = IntArray(1); GLES30.glGetProgramiv(id, GLES30.GL_ACTIVE_UNIFORMS, n, 0)
        val size = IntArray(1); val type = IntArray(1)
        GLES30.glUseProgram(id)
        var unit = 0
        for (k in 0 until n[0]) {
            val name = GLES30.glGetActiveUniform(id, k, size, 0, type, 0).substringBefore('[')
            if (type[0] in SAMPLER_TYPES && size[0] == 1) {
                samplerUnits[name] = unit
                GLES30.glUniform1i(GLES30.glGetUniformLocation(id, name), unit)
                unit++
            }
        }
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw GlException("compile: " + GLES30.glGetShaderInfoLog(s) + "\n" + src.lines().take(5).joinToString("\n"))
        return s
    }

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String) = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun f(name: String, v: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform1f(l, v) }
    fun f(name: String, v: Double) = f(name, v.toFloat())
    fun i(name: String, v: Int) { val l = loc(name); if (l >= 0) GLES30.glUniform1i(l, v) }
    fun v2(name: String, x: Float, y: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform2f(l, x, y) }
    fun v3(name: String, x: Float, y: Float, z: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform3f(l, x, y, z) }
    fun v4(name: String, x: Float, y: Float, z: Float, w: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform4f(l, x, y, z, w) }
    fun fv(name: String, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform1fv(l, v.size, v, 0) }
    fun v3v(name: String, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform3fv(l, v.size / 3, v, 0) }
    /** Row-major 3×3 → GLSL column-major mat3. */
    fun mat3(name: String, m: DoubleArray) {
        val l = loc(name); if (l < 0) return
        GLES30.glUniformMatrix3fv(l, 1, true, FloatArray(9) { m[it].toFloat() }, 0)
    }
    fun mat3v(name: String, ms: List<DoubleArray>) {
        val l = loc(name); if (l < 0 || ms.isEmpty()) return
        val arr = FloatArray(9 * ms.size); ms.forEachIndexed { k, m -> for (j in 0 until 9) arr[k * 9 + j] = m[j].toFloat() }
        GLES30.glUniformMatrix3fv(l, ms.size, true, arr, 0)
    }
    fun tex(name: String, unit: Int, target: Int, texId: Int) {
        val u = samplerUnits[name] ?: return // sampler optimised out by the compiler
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u); GLES30.glBindTexture(target, texId)
    }

    companion object {
        private val SAMPLER_TYPES = setOf(GLES30.GL_SAMPLER_2D, GLES30.GL_SAMPLER_3D, GLES30.GL_SAMPLER_CUBE, GLES30.GL_SAMPLER_2D_SHADOW,
            GLES30.GL_SAMPLER_2D_ARRAY, GLES30.GL_INT_SAMPLER_2D, GLES30.GL_UNSIGNED_INT_SAMPLER_2D, 0x8D66 /* GL_SAMPLER_EXTERNAL_OES */)
    }
    fun release() = GLES30.glDeleteProgram(id)
}

/** 2D texture (RGBA8, linear filtering, clamp). */
class Texture(var width: Int, var height: Int, val id: Int = genTex()) {
    companion object {
        fun genTex(target: Int = GLES30.GL_TEXTURE_2D): Int {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(target, t[0])
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        fun external(): Int = genTex(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        fun fromBitmap(b: Bitmap, existing: Texture? = null): Texture {
            val t = existing ?: Texture(b.width, b.height)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0) // Android uploads premultiplied
            t.width = b.width; t.height = b.height
            return t
        }

        /** 3D LUT texture (size³, RGB float data in 0..1, R fastest). */
        fun lut3d(size: Int, data: FloatArray): Int {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, t[0])
            for (p in listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val bb = ByteBuffer.allocateDirect(size * size * size * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until size * size * size) {
                bb.put((data[i * 3] * 255 + 0.5f).toInt().coerceIn(0, 255).toByte())
                bb.put((data[i * 3 + 1] * 255 + 0.5f).toInt().coerceIn(0, 255).toByte())
                bb.put((data[i * 3 + 2] * 255 + 0.5f).toInt().coerceIn(0, 255).toByte())
                bb.put(255.toByte())
            }
            bb.position(0)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, size, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            return t[0]
        }

        /** 256×1 RGBA lookup (curves). */
        fun curve(data: ByteArray): Int {
            val id = genTex()
            val bb = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder()); bb.put(data); bb.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 256, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            return id
        }
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)
}

/** Framebuffer with a colour texture attachment. */
class Fbo(var width: Int, var height: Int) {
    val texture = Texture(width, height)
    val id: Int

    init {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.id)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); id = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture.id, 0)
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("FBO incomplete 0x${Integer.toHexString(st)}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun bind() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id); GLES30.glViewport(0, 0, width, height) }

    fun clear(r: Float = 0f, g: Float = 0f, b: Float = 0f, a: Float = 0f) {
        bind(); GLES30.glClearColor(r, g, b, a); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /** Reads pixels (RGBA8, bottom-up) into a premultiplied ARGB bitmap (top-down). */
    fun toBitmap(): Bitmap {
        bind()
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply { postScale(1f, -1f, width / 2f, height / 2f) }
        return Bitmap.createBitmap(bmp, 0, 0, width, height, m, false).also { if (it !== bmp) bmp.recycle() }
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(id), 0); texture.release()
    }
}

/** Full-screen triangle strip (positions in clip space, attribute 0). */
object Quad {
    private var vbo = 0
    private val data: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    fun draw() {
        if (vbo == 0 || !GLES30.glIsBuffer(vbo)) {
            val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); vbo = b[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, data, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun reset() { vbo = 0 }
}
