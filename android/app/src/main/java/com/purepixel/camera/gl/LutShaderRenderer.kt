package com.purepixel.camera.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.purepixel.camera.model.LightroomPreset

class LutShaderRenderer(
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit,
    private val onAnalysis: (PreviewAnalysis) -> Unit = { }
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "LutShaderRenderer"
        const val LUT_SIZE = 32

        private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
uniform mat4 uSTMatrix;

void main() {
    gl_Position = aPosition;
    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// Grain coordinates span the normalized image width; mediump quantization can
// otherwise reintroduce visible tiles on high-resolution/zoomed previews.
precision highp float;
precision highp int;
precision mediump samplerExternalOES;
precision mediump sampler3D;

in vec2 vTexCoord;
out vec4 fragColor;

uniform samplerExternalOES uCameraTexture;
uniform sampler3D uLutTexture3D;
uniform float uEnableLut;
uniform float uIntensity;
uniform float uGrain;
uniform float uExtraGrain;
uniform float uHalation;
uniform float uZebra;
uniform float uFocusPeaking;
uniform float uClarity;
uniform float uSharpness;
uniform float uSharpenRadius;
uniform float uSharpenMasking;
uniform vec2 uNoiseReduction;
uniform float uGrainSize;
uniform float uGrainRoughness;
uniform vec2 uResolution;

float grainHash(ivec2 p) {
    uint n = uint(p.x) * 374761393u + uint(p.y) * 668265263u;
    n = (n ^ (n >> 13u)) * 1274126177u;
    n = n ^ (n >> 16u);
    return float(n & 65535u) / 32767.5 - 1.0;
}

float radialGrain(ivec2 cell, vec2 delta) {
    float falloff = max(0.0, 0.5 - dot(delta, delta));
    float roundKernel = falloff * falloff * falloff * falloff;
    return roundKernel * grainHash(cell);
}

// A triangular simplex lattice avoids the axis-aligned blocks produced by the
// old floor-based noise. Every contribution has a circular radial kernel.
float roundFilmGrain(vec2 p) {
    const float F2 = 0.3660254038;
    const float G2 = 0.2113248654;
    ivec2 cell = ivec2(floor(p + dot(p, vec2(F2))));
    float unskew = float(cell.x + cell.y) * G2;
    vec2 d0 = p - vec2(cell) + unskew;
    ivec2 corner = d0.x > d0.y ? ivec2(1, 0) : ivec2(0, 1);
    vec2 d1 = d0 - vec2(corner) + G2;
    vec2 d2 = d0 - 1.0 + 2.0 * G2;
    return 8.0 * (
        radialGrain(cell, d0) +
        radialGrain(cell + corner, d1) +
        radialGrain(cell + ivec2(1), d2)
    );
}

void main() {
    vec4 cameraColor = texture(uCameraTexture, vTexCoord);
    // Shared local-detail pass for clarity, sharpening and noise reduction.
    float detailAmount = abs(uClarity) + uSharpness + uNoiseReduction.x + uNoiseReduction.y;
    if (detailAmount > 0.001) {
        vec2 detailStep = uSharpenRadius / max(uResolution, vec2(1.0));
        vec3 soft = (texture(uCameraTexture, vTexCoord + vec2(detailStep.x, 0.0)).rgb +
                     texture(uCameraTexture, vTexCoord - vec2(detailStep.x, 0.0)).rgb +
                     texture(uCameraTexture, vTexCoord + vec2(0.0, detailStep.y)).rgb +
                     texture(uCameraTexture, vTexCoord - vec2(0.0, detailStep.y)).rgb) * 0.25;
        float centerL = dot(cameraColor.rgb, vec3(0.2126, 0.7152, 0.0722));
        float blurL = dot(soft, vec3(0.2126, 0.7152, 0.0722));
        float edge = abs(centerL - blurL);
        float clarityMask = smoothstep(0.08, 0.35, centerL) * (1.0 - smoothstep(0.82, 1.0, centerL));
        float targetL = mix(centerL, blurL, uNoiseReduction.x);
        vec3 centerChroma = cameraColor.rgb - vec3(centerL);
        vec3 blurChroma = soft - vec3(blurL);
        vec3 denoised = vec3(targetL) + mix(centerChroma, blurChroma, uNoiseReduction.y);
        float edgeMask = smoothstep(uSharpenMasking * 0.25, uSharpenMasking * 0.25 + 0.08, edge);
        cameraColor.rgb = denoised + (cameraColor.rgb - soft) * (uSharpness * edgeMask + uClarity * clarityMask);
    }
    
    // Trilinear 3D LUT sampling in OpenGL ES 3.0
    vec3 graded = texture(uLutTexture3D, clamp(cameraColor.rgb, 0.0, 1.0)).rgb;
    vec3 lutColor = mix(cameraColor.rgb, graded, step(0.5, uEnableLut) * uIntensity);
    if (uHalation > 0.001) {
        vec2 glowStep = 3.0 / max(uResolution, vec2(1.0));
        vec3 glow = texture(uCameraTexture, vTexCoord + vec2(glowStep.x, 0.0)).rgb;
        glow += texture(uCameraTexture, vTexCoord - vec2(glowStep.x, 0.0)).rgb;
        glow += texture(uCameraTexture, vTexCoord + vec2(0.0, glowStep.y)).rgb;
        glow += texture(uCameraTexture, vTexCoord - vec2(0.0, glowStep.y)).rgb;
        float hot = smoothstep(0.68, 1.0, dot(glow * 0.25, vec3(0.2126, 0.7152, 0.0722)));
        lutColor += vec3(0.34, 0.075, -0.025) * hot * uHalation;
    }
    float combinedGrain = uGrain + uExtraGrain * 0.18;
    if (combinedGrain > 0.0001) {
        float shortEdge = max(1.0, min(uResolution.x, uResolution.y));
        vec2 grainCoord = gl_FragCoord.xy * (1000.0 / shortEdge) / uGrainSize;
        float rounded = roundFilmGrain(grainCoord);
        // Roughness reshapes the same circular particles instead of evaluating a
        // second expensive noise octave for every preview pixel.
        float roughened = rounded * (1.55 - 1.1 * abs(rounded));
        float noise = mix(rounded, roughened, uGrainRoughness);
        float luminance = dot(lutColor, vec3(0.2126, 0.7152, 0.0722));
        float midtonePresence = 1.0 - abs(luminance * 2.0 - 1.0);
        float grainMask = 0.72 + 0.28 * midtonePresence;
        lutColor = clamp(lutColor + noise * combinedGrain * grainMask, 0.0, 1.0);
    }
    if (uFocusPeaking > 0.5) {
        vec2 edgeStep = 1.5 / max(uResolution, vec2(1.0));
        vec3 horizontal = texture(uCameraTexture, vTexCoord + vec2(edgeStep.x, 0.0)).rgb -
            texture(uCameraTexture, vTexCoord - vec2(edgeStep.x, 0.0)).rgb;
        vec3 vertical = texture(uCameraTexture, vTexCoord + vec2(0.0, edgeStep.y)).rgb -
            texture(uCameraTexture, vTexCoord - vec2(0.0, edgeStep.y)).rgb;
        float edge = length(horizontal) + length(vertical);
        float peak = smoothstep(0.24, 0.42, edge);
        lutColor = mix(lutColor, vec3(1.0, 0.12, 0.08), peak * 0.92);
    }
    if (uZebra > 0.5) {
        float threshold = uZebra > 1.5 ? 0.78 : 0.91;
        float clipped = smoothstep(threshold, threshold + 0.035, dot(lutColor, vec3(0.2126, 0.7152, 0.0722)));
        float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 10.0));
        vec3 zebraColor = mix(vec3(0.03), vec3(1.0), stripe);
        lutColor = mix(lutColor, zebraColor, clipped * (uZebra > 1.5 ? 0.9 : 0.62));
    }
    fragColor = vec4(clamp(lutColor, 0.0, 1.0), cameraColor.a);
}
"""

        private const val SAFE_LUT_FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision mediump samplerExternalOES;
precision mediump sampler3D;
in vec2 vTexCoord;
out vec4 fragColor;
uniform samplerExternalOES uCameraTexture;
uniform sampler3D uLutTexture3D;
uniform float uEnableLut;
void main() {
    vec4 cameraColor = texture(uCameraTexture, vTexCoord);
    vec3 filtered = texture(uLutTexture3D, clamp(cameraColor.rgb, 0.0, 1.0)).rgb;
    fragColor = vec4(mix(cameraColor.rgb, filtered, step(0.5, uEnableLut)), cameraColor.a);
}
"""

        private const val PASSTHROUGH_FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision mediump samplerExternalOES;
in vec2 vTexCoord;
out vec4 fragColor;
uniform samplerExternalOES uCameraTexture;
void main() {
    fragColor = texture(uCameraTexture, vTexCoord);
}
"""

        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
    }

    private var programId: Int = 0
    private var cameraTextureId: Int = 0
    private var lutTexture3DId: Int = 0
    private var vertexArrayId: Int = 0
    private var vertexBufferId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var stMatrixHandle: Int = 0
    private var cameraTextureHandle: Int = 0
    private var lutTextureHandle: Int = 0
    private var enableLutHandle: Int = 0
    private var intensityHandle: Int = 0
    private var grainHandle: Int = 0
    private var extraGrainHandle: Int = 0
    private var halationHandle: Int = 0
    private var zebraHandle: Int = 0
    private var focusPeakingHandle: Int = 0
    private var clarityHandle: Int = 0
    private var sharpnessHandle: Int = 0
    private var sharpenRadiusHandle: Int = 0
    private var sharpenMaskingHandle: Int = 0
    private var noiseReductionHandle: Int = 0
    private var grainSizeHandle: Int = 0
    private var grainRoughnessHandle: Int = 0
    private var resolutionHandle: Int = 0
    private val stMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    @Volatile
    private var updateSurface = false
    @Volatile private var lastFrameCallbackNanos = 0L
    private var textureUnavailableLogged = false

    @Volatile
    var isLutEnabled = false // Default to No Filter (bypassed)
    @Volatile private var grainAmount = 0f
    @Volatile private var lookIntensity = 1f
    @Volatile private var extraGrainAmount = 0f
    @Volatile private var halationAmount = 0f
    @Volatile private var zebraMode = 0f
    @Volatile private var focusPeakingEnabled = 0f
    @Volatile private var previewAnalysisEnabled = false
    @Volatile private var clarityAmount = 0f
    @Volatile private var sharpnessAmount = 0f
    @Volatile private var sharpenRadius = 1f
    @Volatile private var sharpenMasking = 0f
    @Volatile private var luminanceNoiseReduction = 0f
    @Volatile private var colorNoiseReduction = 0f
    @Volatile private var grainSize = 1f
    @Volatile private var grainRoughness = .5f
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastAnalysisNanos = 0L
    private var analysisBuffer: ByteBuffer? = null

    init {
        android.opengl.Matrix.setIdentityM(stMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            Log.w(TAG, "Full effect shader unavailable; using LUT-safe renderer")
            programId = createProgram(VERTEX_SHADER, SAFE_LUT_FRAGMENT_SHADER)
        }
        if (programId == 0) {
            Log.w(TAG, "LUT shader unavailable; using direct camera passthrough")
            programId = createProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT_SHADER)
        }

        stMatrixHandle = GLES30.glGetUniformLocation(programId, "uSTMatrix")
        cameraTextureHandle = GLES30.glGetUniformLocation(programId, "uCameraTexture")
        lutTextureHandle = GLES30.glGetUniformLocation(programId, "uLutTexture3D")
        enableLutHandle = GLES30.glGetUniformLocation(programId, "uEnableLut")
        intensityHandle = GLES30.glGetUniformLocation(programId, "uIntensity")
        grainHandle = GLES30.glGetUniformLocation(programId, "uGrain")
        extraGrainHandle = GLES30.glGetUniformLocation(programId, "uExtraGrain")
        halationHandle = GLES30.glGetUniformLocation(programId, "uHalation")
        zebraHandle = GLES30.glGetUniformLocation(programId, "uZebra")
        focusPeakingHandle = GLES30.glGetUniformLocation(programId, "uFocusPeaking")
        clarityHandle = GLES30.glGetUniformLocation(programId, "uClarity")
        sharpnessHandle = GLES30.glGetUniformLocation(programId, "uSharpness")
        sharpenRadiusHandle = GLES30.glGetUniformLocation(programId, "uSharpenRadius")
        sharpenMaskingHandle = GLES30.glGetUniformLocation(programId, "uSharpenMasking")
        noiseReductionHandle = GLES30.glGetUniformLocation(programId, "uNoiseReduction")
        grainSizeHandle = GLES30.glGetUniformLocation(programId, "uGrainSize")
        grainRoughnessHandle = GLES30.glGetUniformLocation(programId, "uGrainRoughness")
        resolutionHandle = GLES30.glGetUniformLocation(programId, "uResolution")

        // 1. Create External Camera OES Texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // External OES textures must use CLAMP_TO_EDGE. The default REPEAT mode makes
        // the texture incomplete on strict drivers and results in a permanently black preview.
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // 2. Create 3D Texture for LUT
        GLES30.glGenTextures(1, textures, 0)
        lutTexture3DId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        // A trilinearly sampled 32³ cube is visually indistinguishable for these
        // smooth Lightroom controls and requires one eighth of a 64³ cube's work.
        loadDefaultNeutral3DLut()

        // 3. Bind SurfaceTexture
        surfaceTexture = SurfaceTexture(cameraTextureId).apply {
            // Some vendor implementations do not reliably dispatch a listener
            // registered from GLSurfaceView's looper-less GL thread. An explicit
            // main handler keeps WHEN_DIRTY rendering frame-driven and portable.
            setOnFrameAvailableListener(this@LutShaderRenderer, Handler(Looper.getMainLooper()))
            onSurfaceReady(this)
        }

        // GLES 3 does not permit the client-side vertex arrays that GLES 2 drivers
        // often accepted. A real VAO/VBO is required or glDrawArrays renders nothing.
        val vertexArrays = IntArray(1)
        GLES30.glGenVertexArrays(1, vertexArrays, 0)
        vertexArrayId = vertexArrays[0]
        GLES30.glBindVertexArray(vertexArrayId)

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vertexBufferId = buffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId)
        vertexBuffer.position(0)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            QUAD_VERTICES.size * 4,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        logGlError("surface creation")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (updateSurface) {
                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                    textureUnavailableLogged = false
                } catch (e: RuntimeException) {
                    if (!textureUnavailableLogged) {
                        Log.w(TAG, "Camera texture was temporarily unavailable", e)
                        textureUnavailableLogged = true
                    }
                }
                updateSurface = false
            }
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        // Bind Camera External OES Texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(cameraTextureHandle, 0)

        // Bind 3D LUT Texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
        GLES30.glUniform1i(lutTextureHandle, 1)

        GLES30.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        GLES30.glUniform1f(enableLutHandle, if (isLutEnabled) 1.0f else 0.0f)
        GLES30.glUniform1f(intensityHandle, lookIntensity)
        GLES30.glUniform1f(grainHandle, grainAmount)
        GLES30.glUniform1f(extraGrainHandle, extraGrainAmount)
        GLES30.glUniform1f(halationHandle, halationAmount)
        GLES30.glUniform1f(zebraHandle, zebraMode)
        GLES30.glUniform1f(focusPeakingHandle, focusPeakingEnabled)
        GLES30.glUniform1f(clarityHandle, clarityAmount)
        GLES30.glUniform1f(sharpnessHandle, sharpnessAmount)
        GLES30.glUniform1f(sharpenRadiusHandle, sharpenRadius)
        GLES30.glUniform1f(sharpenMaskingHandle, sharpenMasking)
        GLES30.glUniform2f(noiseReductionHandle, luminanceNoiseReduction, colorNoiseReduction)
        GLES30.glUniform1f(grainSizeHandle, grainSize)
        GLES30.glUniform1f(grainRoughnessHandle, grainRoughness)
        GLES30.glUniform2f(resolutionHandle, viewportWidth.toFloat(), viewportHeight.toFloat())

        // Render the camera texture through the GLES 3 VAO.
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        collectPreviewAnalysis()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        lastFrameCallbackNanos = System.nanoTime()
        synchronized(this) {
            updateSurface = true
        }
        requestRender()
    }

    /** Vendor fallback: poll only while SurfaceTexture callbacks have stalled. */
    fun pollFrameIfNeeded() {
        if (System.nanoTime() - lastFrameCallbackNanos < 120_000_000L) return
        synchronized(this) { updateSurface = true }
        requestRender()
    }

    fun update3DLutData(lutBuffer: ByteBuffer, size: Int = LUT_SIZE) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB8,
            size, size, size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            lutBuffer
        )
    }

    fun loadHald8Bitmap(bitmap: Bitmap) {
        val size = 64
        val buffer = ByteBuffer.allocateDirect(size * size * size * 3)
        val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        val pixels = IntArray(512 * 512)
        scaled.getPixels(pixels, 0, 512, 0, 0, 512, 512)

        for (b in 0 until 64) {
            val blockX = (b % 8) * 64
            val blockY = (b / 8) * 64
            for (g in 0 until 64) {
                for (r in 0 until 64) {
                    val x = blockX + r
                    val y = blockY + g
                    val pixel = pixels[y * 512 + x]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    buffer.put(red.toByte())
                    buffer.put(green.toByte())
                    buffer.put(blue.toByte())
                }
            }
        }
        buffer.position(0)
        update3DLutData(buffer, size)
        isLutEnabled = true
        requestRender()
    }

    fun loadLightroomPreset(preset: LightroomPreset, preparedLut: ByteBuffer) {
        update3DLutData(preparedLut, LUT_SIZE)
        grainAmount = preset.grain.coerceIn(0f, 100f) / 100f * .18f
        grainSize = 1f + preset.grainSize.coerceIn(0f, 100f) / 18f
        grainRoughness = preset.grainRoughness.coerceIn(0f, 100f) / 100f
        clarityAmount = preset.clarity.coerceIn(-100f, 100f) / 100f * .55f
        sharpnessAmount = preset.sharpness.coerceIn(0f, 150f) / 100f * (.45f + preset.sharpenDetail.coerceIn(0f,100f)/100f*.55f)
        sharpenRadius = preset.sharpenRadius.coerceIn(.5f, 3f)
        sharpenMasking = preset.sharpenMasking.coerceIn(0f,100f)/100f
        luminanceNoiseReduction = preset.luminanceNoiseReduction.coerceIn(0f,100f)/100f*.7f
        colorNoiseReduction = preset.colorNoiseReduction.coerceIn(0f,100f)/100f*.75f
        isLutEnabled = true
        requestRender()
    }

    fun buildBuiltInLut(presetId: String): ByteBuffer? {
        val colorMatrix = createPresetColorMatrix(presetId)
        if (colorMatrix == null) return null
        val matrix = colorMatrix.array
        val size = LUT_SIZE
        val buffer = ByteBuffer.allocateDirect(size * size * size * 3)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val rf = r / (size - 1f)
            val gf = g / (size - 1f)
            val bf = b / (size - 1f)
            buffer.put(((matrix[0] * rf + matrix[1] * gf + matrix[2] * bf + matrix[4] / 255f).coerceIn(0f, 1f) * 255f).toInt().toByte())
            buffer.put(((matrix[5] * rf + matrix[6] * gf + matrix[7] * bf + matrix[9] / 255f).coerceIn(0f, 1f) * 255f).toInt().toByte())
            buffer.put(((matrix[10] * rf + matrix[11] * gf + matrix[12] * bf + matrix[14] / 255f).coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
        buffer.position(0)
        return buffer
    }

    fun loadBuiltInPreset(preparedLut: ByteBuffer) {
        update3DLutData(preparedLut, LUT_SIZE)
        grainAmount = 0f
        clarityAmount = 0f
        sharpnessAmount = 0f
        luminanceNoiseReduction = 0f
        colorNoiseReduction = 0f
        isLutEnabled = true
        requestRender()
    }

    fun setLookControls(intensity: Float, grain: Float, halation: Float) {
        lookIntensity = intensity.coerceIn(0f, 1f)
        extraGrainAmount = grain.coerceIn(0f, 1f)
        halationAmount = halation.coerceIn(0f, 1f)
        requestRender()
    }

    fun setAssistSettings(zebra: Int, focusPeaking: Boolean) {
        zebraMode = zebra.coerceIn(0, 2).toFloat()
        focusPeakingEnabled = if (focusPeaking) 1f else 0f
        requestRender()
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        previewAnalysisEnabled = enabled
        if (enabled) lastAnalysisNanos = 0L
    }

    private fun collectPreviewAnalysis() {
        if (!previewAnalysisEnabled) return
        val now = System.nanoTime()
        if (now - lastAnalysisNanos < 220_000_000L || viewportWidth <= 1 || viewportHeight <= 1) return
        lastAnalysisNanos = now
        val byteCount = viewportWidth * viewportHeight * 4
        val buffer = analysisBuffer?.takeIf { it.capacity() >= byteCount }
            ?: ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).also { analysisBuffer = it }
        buffer.clear()
        GLES30.glReadPixels(
            0, 0, viewportWidth, viewportHeight,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        val histogram = FloatArray(64)
        val red = FloatArray(40)
        val green = FloatArray(40)
        val blue = FloatArray(40)
        val counts = IntArray(40)
        val xStep = (viewportWidth / 96).coerceAtLeast(2)
        val yStep = (viewportHeight / 72).coerceAtLeast(2)
        var y = 0
        while (y < viewportHeight) {
            var x = 0
            while (x < viewportWidth) {
                val offset = (y * viewportWidth + x) * 4
                val r = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val b = buffer.get(offset + 2).toInt() and 0xff
                val luma = (.2126f * r + .7152f * g + .0722f * b)
                histogram[(luma / 4f).toInt().coerceIn(0, 63)] += 1f
                val column = (x * red.size / viewportWidth).coerceIn(0, red.lastIndex)
                red[column] += r / 255f
                green[column] += g / 255f
                blue[column] += b / 255f
                counts[column]++
                x += xStep
            }
            y += yStep
        }
        val maxBin = histogram.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        for (index in histogram.indices) histogram[index] /= maxBin
        for (index in red.indices) {
            val count = counts[index].coerceAtLeast(1)
            red[index] /= count
            green[index] /= count
            blue[index] /= count
        }
        onAnalysis(PreviewAnalysis(histogram, red, green, blue))
    }

    fun disableLut() {
        isLutEnabled = false
        requestRender()
    }

    private fun loadDefaultNeutral3DLut() {
        val size = LUT_SIZE
        val buffer = ByteBuffer.allocateDirect(size * size * size * 3)
        for (z in 0 until size) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    buffer.put(((x.toFloat() / (size - 1)) * 255).toInt().toByte())
                    buffer.put(((y.toFloat() / (size - 1)) * 255).toInt().toByte())
                    buffer.put(((z.toFloat() / (size - 1)) * 255).toInt().toByte())
                }
            }
        }
        buffer.position(0)
        update3DLutData(buffer, size)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun logGlError(stage: String) {
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "OpenGL error after $stage: 0x${Integer.toHexString(error)}")
            error = GLES30.glGetError()
        }
    }
}
