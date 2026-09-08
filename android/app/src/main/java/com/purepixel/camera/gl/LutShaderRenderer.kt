package com.purepixel.camera.gl

import android.graphics.BitmapFactory
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.EGL14
import android.opengl.EGLExt
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.purepixel.camera.model.LightroomPreset
import com.purepixel.camera.R

class LutShaderRenderer(
    private val context: Context,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit,
    private val onAnalysis: (PreviewAnalysis) -> Unit = { },
    private val onFirstCameraFrame: () -> Unit = { },
    private val acquireTrackingFrame: () -> Boolean = { false },
    private val onTrackingFrame: (FloatArray, Int, Int, Long) -> Unit = { _, _, _, _ -> }
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "LutShaderRenderer"
        const val LUT_SIZE = 32
        private const val ANALYSIS_WIDTH = 96
        private const val ANALYSIS_HEIGHT = 72

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
precision mediump sampler2D;

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

uniform sampler2D uBlueNoise;
uniform vec2 uGrainOffset;

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
        vec2 grainUv = gl_FragCoord.xy / (128.0 * max(uGrainSize, 0.5)) + uGrainOffset;
        float blueNoise = texture(uBlueNoise, grainUv).r * 2.0 - 1.0;
        // Linear texture filtering turns individual noise texels into soft particles;
        // roughness restores harder edges without additional texture samples.
        float roughened = blueNoise * (1.55 - 1.1 * abs(blueNoise));
        float noise = mix(blueNoise, roughened, uGrainRoughness);
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
uniform float uIntensity;
void main() {
    vec4 cameraColor = texture(uCameraTexture, vTexCoord);
    vec3 filtered = texture(uLutTexture3D, clamp(cameraColor.rgb, 0.0, 1.0)).rgb;
    fragColor = vec4(mix(cameraColor.rgb, filtered, uIntensity), cameraColor.a);
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
    private var lutProgramId: Int = 0
    private var passthroughProgramId: Int = 0
    private var cameraTextureId: Int = 0
    private var lutTexture3DId: Int = 0
    private var blueNoiseTextureId: Int = 0
    private var vertexArrayId: Int = 0
    private var vertexBufferId: Int = 0
    private var analysisFramebufferId: Int = 0
    private var analysisTextureId: Int = 0
    private var trackingFramebufferId = 0
    private var trackingTextureId = 0
    private var trackingBuffer: ByteBuffer? = null
    private var lastTrackingNanos = 0L
    private var lastTrackingTextureTimestamp = Long.MIN_VALUE
    private var surfaceTexture: SurfaceTexture? = null
    private var encoderEglSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0
    @Volatile private var recordingFrames = false

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
    private var blueNoiseHandle: Int = 0
    private var grainOffsetHandle: Int = 0
    private var lutStMatrixHandle: Int = 0
    private var lutCameraTextureHandle: Int = 0
    private var lutTextureOnlyHandle: Int = 0
    private var lutIntensityHandle: Int = 0
    private var passthroughStMatrixHandle: Int = 0
    private var passthroughCameraTextureHandle: Int = 0
    private var renderedFrameIndex = 0
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
    private var firstCameraFrameReported = false

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
        trackingFramebufferId = 0
        trackingTextureId = 0
        lastTrackingTextureTimestamp = Long.MIN_VALUE
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        lutProgramId = createProgram(VERTEX_SHADER, SAFE_LUT_FRAGMENT_SHADER)
        passthroughProgramId = createProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT_SHADER)
        if (programId == 0) Log.w(TAG, "Full effect shader unavailable; using simplified renderer")
        // Vendor GL compilers vary widely. A failed optional preview shader must
        // never terminate the complete Activity during startup.
        if (lutProgramId == 0 && passthroughProgramId == 0) {
            Log.e(TAG, "No camera shader could be compiled; preview rendering is disabled")
        }

        if (programId != 0) {
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
            blueNoiseHandle = GLES30.glGetUniformLocation(programId, "uBlueNoise")
            grainOffsetHandle = GLES30.glGetUniformLocation(programId, "uGrainOffset")
        }

        if (lutProgramId != 0) {
            lutStMatrixHandle = GLES30.glGetUniformLocation(lutProgramId, "uSTMatrix")
            lutCameraTextureHandle = GLES30.glGetUniformLocation(lutProgramId, "uCameraTexture")
            lutTextureOnlyHandle = GLES30.glGetUniformLocation(lutProgramId, "uLutTexture3D")
            lutIntensityHandle = GLES30.glGetUniformLocation(lutProgramId, "uIntensity")
        }
        if (passthroughProgramId != 0) {
            passthroughStMatrixHandle = GLES30.glGetUniformLocation(passthroughProgramId, "uSTMatrix")
            passthroughCameraTextureHandle = GLES30.glGetUniformLocation(passthroughProgramId, "uCameraTexture")
        }

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

        // One filtered blue-noise lookup replaces dozens of integer/hash/branch ALU
        // operations per pixel in the former procedural simplex-grain function.
        GLES30.glGenTextures(1, textures, 0)
        blueNoiseTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blueNoiseTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        BitmapFactory.decodeResource(context.resources, R.drawable.blue_noise_128)?.let { bitmap ->
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

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

        val analysisIds = IntArray(1)
        GLES30.glGenTextures(1, analysisIds, 0)
        analysisTextureId = analysisIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, analysisTextureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            ANALYSIS_WIDTH, ANALYSIS_HEIGHT, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glGenFramebuffers(1, analysisIds, 0)
        analysisFramebufferId = analysisIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, analysisFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, analysisTextureId, 0
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Small preview-analysis framebuffer is unavailable")
            GLES30.glDeleteFramebuffers(1, intArrayOf(analysisFramebufferId), 0)
            analysisFramebufferId = 0
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        logGlError("surface creation")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        var consumedCameraFrame = false
        synchronized(this) {
            if (updateSurface) {
                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                    textureUnavailableLogged = false
                    consumedCameraFrame = true
                } catch (e: RuntimeException) {
                    if (!textureUnavailableLogged) {
                        Log.w(TAG, "Camera texture was temporarily unavailable", e)
                        textureUnavailableLogged = true
                    }
                }
                updateSurface = false
            }
        }

        if (consumedCameraFrame) renderedFrameIndex++
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val activeProgram = bindBestProgram(viewportWidth, viewportHeight)
        if (activeProgram == 0) return

        // Render the camera texture through the GLES 3 VAO.
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        collectPreviewAnalysis(activeProgram)
        if (consumedCameraFrame) collectTrackingFrame()
        if (consumedCameraFrame && recordingFrames) renderEncoderFrame()
        if (consumedCameraFrame && !firstCameraFrameReported) {
            firstCameraFrameReported = true
            onFirstCameraFrame()
        }
    }

    private fun bindBestProgram(width: Int, height: Int, includeMonitorAssists: Boolean = true): Int {
        val needsFullEffects = grainAmount > .0001f || extraGrainAmount > .0001f ||
            halationAmount > .0001f ||
            (includeMonitorAssists && (zebraMode > .5f || focusPeakingEnabled > .5f)) ||
            kotlin.math.abs(clarityAmount) > .0001f || sharpnessAmount > .0001f ||
            luminanceNoiseReduction > .0001f || colorNoiseReduction > .0001f
        val activeProgram = when {
            needsFullEffects && programId != 0 -> programId
            isLutEnabled && lutProgramId != 0 -> lutProgramId
            passthroughProgramId != 0 -> passthroughProgramId
            else -> lutProgramId
        }
        if (activeProgram == 0) return 0
        GLES30.glUseProgram(activeProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        when (activeProgram) {
            programId -> {
                GLES30.glUniform1i(cameraTextureHandle, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
                GLES30.glUniform1i(lutTextureHandle, 1)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blueNoiseTextureId)
                GLES30.glUniform1i(blueNoiseHandle, 2)
                GLES30.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
                GLES30.glUniform1f(enableLutHandle, if (isLutEnabled) 1f else 0f)
                GLES30.glUniform1f(intensityHandle, lookIntensity)
                GLES30.glUniform1f(grainHandle, grainAmount)
                GLES30.glUniform1f(extraGrainHandle, extraGrainAmount)
                GLES30.glUniform1f(halationHandle, halationAmount)
                GLES30.glUniform1f(zebraHandle, if (includeMonitorAssists) zebraMode else 0f)
                GLES30.glUniform1f(focusPeakingHandle, if (includeMonitorAssists) focusPeakingEnabled else 0f)
                GLES30.glUniform1f(clarityHandle, clarityAmount)
                GLES30.glUniform1f(sharpnessHandle, sharpnessAmount)
                GLES30.glUniform1f(sharpenRadiusHandle, sharpenRadius)
                GLES30.glUniform1f(sharpenMaskingHandle, sharpenMasking)
                GLES30.glUniform2f(noiseReductionHandle, luminanceNoiseReduction, colorNoiseReduction)
                GLES30.glUniform1f(grainSizeHandle, grainSize)
                GLES30.glUniform1f(grainRoughnessHandle, grainRoughness)
                GLES30.glUniform2f(resolutionHandle, width.toFloat(), height.toFloat())
                GLES30.glUniform2f(
                    grainOffsetHandle,
                    ((renderedFrameIndex * 37) and 127) / 128f,
                    ((renderedFrameIndex * 73) and 127) / 128f
                )
            }
            lutProgramId -> {
                GLES30.glUniform1i(lutCameraTextureHandle, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
                GLES30.glUniform1i(lutTextureOnlyHandle, 1)
                GLES30.glUniformMatrix4fv(lutStMatrixHandle, 1, false, stMatrix, 0)
                GLES30.glUniform1f(lutIntensityHandle, lookIntensity)
            }
            else -> {
                GLES30.glUniform1i(passthroughCameraTextureHandle, 0)
                GLES30.glUniformMatrix4fv(passthroughStMatrixHandle, 1, false, stMatrix, 0)
            }
        }
        return activeProgram
    }

    /** Creates a second EGL window surface in the GLSurfaceView's current context. */
    fun attachEncoderSurface(surface: Surface, width: Int, height: Int) {
        detachEncoderSurface()
        val display = EGL14.eglGetCurrentDisplay()
        val previewSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        check(display != EGL14.EGL_NO_DISPLAY && previewSurface != EGL14.EGL_NO_SURFACE) {
            "The preview EGL surface is not current"
        }
        val configId = IntArray(1)
        check(EGL14.eglQuerySurface(display, previewSurface, EGL14.EGL_CONFIG_ID, configId, 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE),
                0,
                configs,
                0,
                1,
                count,
                0
            ) && count[0] == 1
        ) { "Unable to resolve the preview EGL config" }
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            display,
            requireNotNull(configs[0]),
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(encoderEglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create video EGL surface" }
        encoderWidth = width
        encoderHeight = height
    }

    fun setRecordingFrames(active: Boolean) {
        recordingFrames = active
    }

    fun detachEncoderSurface() {
        recordingFrames = false
        val surface = encoderEglSurface
        if (surface == EGL14.EGL_NO_SURFACE) return
        val display = EGL14.eglGetCurrentDisplay()
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, surface)
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        encoderWidth = 0
        encoderHeight = 0
    }

    private fun renderEncoderFrame() {
        val target = encoderEglSurface
        if (target == EGL14.EGL_NO_SURFACE) return
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val previewDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previewRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(display, target, target, context)) {
            recordingFrames = false
            Log.e(TAG, "Unable to make the video EGL surface current")
            return
        }
        GLES30.glViewport(0, 0, encoderWidth, encoderHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (bindBestProgram(encoderWidth, encoderHeight, includeMonitorAssists = false) != 0) {
            GLES30.glBindVertexArray(vertexArrayId)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
            val timestamp = surfaceTexture?.timestamp ?: System.nanoTime()
            EGLExt.eglPresentationTimeANDROID(display, target, timestamp)
            EGL14.eglSwapBuffers(display, target)
        }
        EGL14.eglMakeCurrent(display, previewDraw, previewRead, context)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
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

    private fun collectPreviewAnalysis(activeProgram: Int) {
        if (!previewAnalysisEnabled || analysisFramebufferId == 0) return
        val now = System.nanoTime()
        if (now - lastAnalysisNanos < 250_000_000L || viewportWidth <= 1 || viewportHeight <= 1) return
        lastAnalysisNanos = now
        val byteCount = ANALYSIS_WIDTH * ANALYSIS_HEIGHT * 4
        val buffer = analysisBuffer?.takeIf { it.capacity() >= byteCount }
            ?: ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).also { analysisBuffer = it }
        buffer.clear()

        // Render the already-bound preview program into a tiny target. Reading 27 KiB
        // avoids the synchronous ~2.6 MiB full-preview GPU readback used previously.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, analysisFramebufferId)
        GLES30.glViewport(0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        if (activeProgram == programId) {
            GLES30.glUniform2f(resolutionHandle, ANALYSIS_WIDTH.toFloat(), ANALYSIS_HEIGHT.toFloat())
        }
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glReadPixels(
            0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        val histogram = FloatArray(64)
        val red = FloatArray(40)
        val green = FloatArray(40)
        val blue = FloatArray(40)
        val counts = IntArray(40)
        var y = 0
        while (y < ANALYSIS_HEIGHT) {
            var x = 0
            while (x < ANALYSIS_WIDTH) {
                val offset = (y * ANALYSIS_WIDTH + x) * 4
                val r = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val b = buffer.get(offset + 2).toInt() and 0xff
                val luma = (.2126f * r + .7152f * g + .0722f * b)
                histogram[(luma / 4f).toInt().coerceIn(0, 63)] += 1f
                val column = (x * red.size / ANALYSIS_WIDTH).coerceIn(0, red.lastIndex)
                red[column] += r / 255f
                green[column] += g / 255f
                blue[column] += b / 255f
                counts[column]++
                x++
            }
            y++
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

    private fun collectTrackingFrame() {
        val now = System.nanoTime()
        val textureTimestamp = surfaceTexture?.timestamp ?: return
        if (textureTimestamp == lastTrackingTextureTimestamp ||
            now - lastTrackingNanos < 83_000_000L || passthroughProgramId == 0) return
        if (!acquireTrackingFrame()) return
        lastTrackingNanos = now
        lastTrackingTextureTimestamp = textureTimestamp
        val width = 160
        val height = 212
        try {
            if (trackingFramebufferId == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                trackingTextureId = ids[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trackingTextureId)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glGenFramebuffers(1, ids, 0)
                trackingFramebufferId = ids[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trackingFramebufferId)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, trackingTextureId, 0)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trackingFramebufferId)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glViewport(0, 0, width, height)
            // Use the preview's exact transform, but exclude LUTs, grain and focus peaking.
            GLES30.glUseProgram(passthroughProgramId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES30.glUniform1i(passthroughCameraTextureHandle, 0)
            GLES30.glUniformMatrix4fv(passthroughStMatrixHandle, 1, false, stMatrix, 0)
            GLES30.glBindVertexArray(vertexArrayId)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
            val buffer = trackingBuffer ?: ByteBuffer.allocateDirect(width * height * 4)
                .also { trackingBuffer = it }
            buffer.clear()
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            val luminance = FloatArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                // GL readback starts at the bottom; touch and overlay coordinates start at the top.
                val offset = ((height - 1 - y) * width + x) * 4
                luminance[y * width + x] = .2126f * (buffer.get(offset).toInt() and 255) +
                    .7152f * (buffer.get(offset + 1).toInt() and 255) +
                    .0722f * (buffer.get(offset + 2).toInt() and 255)
            }
            onTrackingFrame(luminance, width, height, now)
        } catch (e: Exception) {
            Log.w(TAG, "Tracking preview unavailable", e)
            onTrackingFrame(FloatArray(0), 0, 0, now)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        }
    }

    fun disableLut() {
        isLutEnabled = false
        grainAmount = 0f
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

    fun releaseSurface() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
    }

    fun releaseGlResources() {
        if (trackingFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(trackingFramebufferId), 0)
            trackingFramebufferId = 0
        }
        if (trackingTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(trackingTextureId), 0)
            trackingTextureId = 0
        }
        trackingBuffer = null
        if (analysisFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(analysisFramebufferId), 0)
            analysisFramebufferId = 0
        }
        val textures = intArrayOf(cameraTextureId, lutTexture3DId, blueNoiseTextureId, analysisTextureId)
            .filter { it != 0 }
            .toIntArray()
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
        if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
        intArrayOf(programId, lutProgramId, passthroughProgramId)
            .filter { it != 0 }
            .distinct()
            .forEach(GLES30::glDeleteProgram)
        analysisBuffer = null
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
        GLES30.glDetachShader(program, vertexShader)
        GLES30.glDetachShader(program, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
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
