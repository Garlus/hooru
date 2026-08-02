package com.purepixel.camera.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.graphics.PixelFormat
import android.view.TextureView
import android.view.ViewGroup
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import android.view.Surface
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.purepixel.camera.model.Preset
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class PreviewAnalysis(
    val luminanceHistogram: FloatArray = FloatArray(64),
    val redWaveform: FloatArray = FloatArray(40),
    val greenWaveform: FloatArray = FloatArray(40),
    val blueWaveform: FloatArray = FloatArray(40)
)

class GLCameraView(
    context: Context,
    onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView(context) {

    @Volatile private var renderingActive = false
    private var analysisListener: (PreviewAnalysis) -> Unit = { }
    val renderer: LutShaderRenderer = LutShaderRenderer(
        onSurfaceReady = onSurfaceReady,
        requestRender = { if (renderingActive) requestRender() },
        onAnalysis = { analysis -> mainHandler.post { analysisListener(analysis) } }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lutExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HooruLutBuilder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val lutPreloadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HooruLutPreloader").apply { priority = Thread.MIN_PRIORITY }
    }
    private val lutCache = ConcurrentHashMap<String, ByteBuffer>()
    private val presetGeneration = AtomicInteger(0)
    private val previewWatchdog = object : Runnable {
        override fun run() {
            if (!renderingActive) return
            renderer.pollFrameIfNeeded()
            mainHandler.postDelayed(this, 33L)
        }
    }
    init {
        setEGLContextClientVersion(3)
        // Keep the SurfaceView above Compose's opaque window layer, but below UI
        // elements that share the window. Its bounds remain the preview rectangle.
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.OPAQUE)
        // The preview is displayed in a relatively small portrait card. Rendering
        // its GL backing surface at 720p avoids shading ~1.5 million pixels per
        // camera frame while Android scales the result to the physical view size.
        // Still captures keep using the camera's maximum JPEG resolution.
        holder.setFixedSize(720, 960)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        // Draw exactly once per camera frame instead of spinning at display refresh rate.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onResume() {
        if (renderingActive) return
        renderingActive = true
        super.onResume()
        requestRender()
        mainHandler.removeCallbacks(previewWatchdog)
        mainHandler.post(previewWatchdog)
    }

    override fun onPause() {
        if (!renderingActive) return
        renderingActive = false
        mainHandler.removeCallbacks(previewWatchdog)
        super.onPause()
    }

    fun applyPreset(preset: Preset) {
        val generation = presetGeneration.incrementAndGet()
        if (preset.id == "no_filter" || preset.id == "android_processing") {
            queueEvent {
                if (generation == presetGeneration.get()) {
                    renderer.disableLut()
                    renderer.setLookControls(preset.intensity, preset.grain, preset.halation)
                }
            }
            return
        }

        lutExecutor.execute {
            if (generation != presetGeneration.get()) return@execute
            val prepared = prepareLut(preset) ?: return@execute
            if (generation != presetGeneration.get()) return@execute
            val upload = prepared.duplicate().apply { position(0) }
            queueEvent {
                if (generation != presetGeneration.get()) return@queueEvent
                preset.lightroom?.let { renderer.loadLightroomPreset(it, upload) }
                    ?: renderer.loadBuiltInPreset(upload)
                renderer.setLookControls(preset.intensity, preset.grain, preset.halation)
            }
        }
    }

    /**
     * Builds the small set of commonly used LUTs without occupying the executor
     * that handles an active user selection. A page turn can therefore always
     * jump ahead of background preparation work.
     */
    fun preloadPresets(presets: List<Preset>) {
        val uncached = presets.filterNot {
            it.id == "no_filter" || it.id == "android_processing" || lutCache.containsKey(it.id)
        }
        if (uncached.isEmpty()) return
        lutPreloadExecutor.execute {
            uncached.forEach(::prepareLut)
        }
    }

    private fun prepareLut(preset: Preset): ByteBuffer? {
        lutCache[preset.id]?.let { return it }
        val generated = preset.lightroom?.buildLut(LutShaderRenderer.LUT_SIZE)
            ?: renderer.buildBuiltInLut(preset.id)
            ?: return null
        val cached = generated.asReadOnlyBuffer().apply { position(0) }
        return lutCache.putIfAbsent(preset.id, cached) ?: cached
    }

    fun setAssistSettings(zebraMode: Int, focusPeaking: Boolean) {
        queueEvent { renderer.setAssistSettings(zebraMode, focusPeaking) }
    }

    fun setAnalysisListener(listener: (PreviewAnalysis) -> Unit) {
        analysisListener = listener
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        queueEvent { renderer.setAnalysisEnabled(enabled) }
    }

    fun clearPreset() {
        val generation = presetGeneration.incrementAndGet()
        queueEvent {
            if (generation == presetGeneration.get()) renderer.disableLut()
        }
    }
}

@Composable
fun CameraPreviewGL(
    modifier: Modifier = Modifier,
    onSurfaceReady: (SurfaceTexture) -> Unit,
    onGLViewReady: (GLCameraView) -> Unit = { },
    onPreviewReady: (GLCameraView) -> Unit = { },
    onPreviewFrame: (Bitmap) -> Unit = { },
    onPreviewAnalysis: (PreviewAnalysis) -> Unit = { }
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraView by remember { mutableStateOf<GLCameraView?>(null) }
    AndroidView(
        factory = { ctx ->
            GLCameraView(ctx, onSurfaceReady).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                cameraView = this
                setAnalysisListener(onPreviewAnalysis)
                onGLViewReady(this)
                onPreviewReady(this)
            }
        },
        modifier = modifier
    )
    DisposableEffect(lifecycleOwner, cameraView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> cameraView?.onResume()
                Lifecycle.Event.ON_PAUSE -> cameraView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) cameraView?.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraView?.onPause()
        }
    }
}

fun createPresetColorMatrix(presetId: String): ColorMatrix? {
    if (presetId == "no_filter" || presetId == "android_processing") return null
    return when (presetId) {
        "hooru_look" -> ColorMatrix(floatArrayOf(
            1.08f, 0.02f, 0.01f, 0f, 3f,
            0.01f, 1.04f, 0.02f, 0f, 1f,
            0.01f, 0.03f, 0.96f, 0f, -2f,
            0f, 0f, 0f, 1f, 0f
        ))
        "silver_push" -> ColorMatrix(floatArrayOf(
            .36f, .72f, .12f, 0f, -22f, .36f, .72f, .12f, 0f, -22f,
            .36f, .72f, .12f, 0f, -22f, 0f, 0f, 0f, 1f, 0f
        ))
        "noir_halide" -> ColorMatrix(floatArrayOf(
            .44f, .87f, .14f, 0f, -48f, .44f, .87f, .14f, 0f, -48f,
            .44f, .87f, .14f, 0f, -48f, 0f, 0f, 0f, 1f, 0f
        ))
        "infra_flora" -> ColorMatrix(floatArrayOf(
            .42f, 1.24f, -.16f, 0f, 4f, .06f, .22f, .34f, 0f, -5f,
            .12f, .08f, 1.08f, 0f, 8f, 0f, 0f, 0f, 1f, 0f
        ))
        "thermal_bloom" -> ColorMatrix(floatArrayOf(
            .72f, 1.22f, .08f, 0f, 8f, .18f, .12f, .42f, 0f, -8f,
            .30f, .04f, 1.20f, 0f, 12f, 0f, 0f, 0f, 1f, 0f
        ))
        "clean_frame", "soft_daylight" -> ColorMatrix(floatArrayOf(
            1.02f, .01f, -.01f, 0f, 2f, .01f, 1.01f, -.01f, 0f, 2f,
            -.01f, .02f, .98f, 0f, 0f, 0f, 0f, 0f, 1f, 0f
        ))
        "coastal_clear" -> ColorMatrix(floatArrayOf(
            .98f, .02f, .02f, 0f, 0f, 0f, 1.03f, .02f, 0f, 2f,
            .01f, .05f, 1.03f, 0f, 3f, 0f, 0f, 0f, 1f, 0f
        ))
        "muted_city", "summer_glass" -> ColorMatrix(floatArrayOf(
            1.01f, .02f, -.01f, 0f, 1f, .02f, .99f, -.01f, 0f, 1f,
            .01f, .03f, .95f, 0f, -1f, 0f, 0f, 0f, 1f, 0f
        ))
        else -> ColorMatrix()
    }
}

fun TextureView.applyPreviewPreset(presetId: String) {
    val matrix = createPresetColorMatrix(presetId)
    if (matrix == null) {
        setLayerType(TextureView.LAYER_TYPE_NONE, null)
        return
    }
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    setLayerType(TextureView.LAYER_TYPE_HARDWARE, paint)
}

fun TextureView.configureCameraPreview(previewSize: Size) {
    if (width == 0 || height == 0) return
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90, Surface.ROTATION_270 -> {
            val bufferRect = RectF(
                0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat()
            )
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                height.toFloat() / previewSize.height,
                width.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(
                90f * ((display?.rotation ?: Surface.ROTATION_0) - 2),
                centerX,
                centerY
            )
        }
        Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
        else -> {
            // Re-applying a known transform after resume prevents TextureView from
            // retaining a stale scale matrix from the external gallery activity.
            matrix.reset()
        }
    }
    setTransform(matrix)
}
