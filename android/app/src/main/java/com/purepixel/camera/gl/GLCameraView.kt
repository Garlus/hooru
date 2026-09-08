package com.purepixel.camera.gl

import com.purepixel.camera.tracking.SubjectTracker
import com.purepixel.camera.tracking.TrackingState
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.graphics.PixelFormat
import android.view.ViewGroup
import android.view.Surface
import android.graphics.ColorMatrix
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

private const val EGL_RECORDABLE_ANDROID = 0x3142

data class PreviewAnalysis(
    val luminanceHistogram: FloatArray = FloatArray(64),
    val redWaveform: FloatArray = FloatArray(40),
    val greenWaveform: FloatArray = FloatArray(40),
    val blueWaveform: FloatArray = FloatArray(40)
)

@android.annotation.SuppressLint("ViewConstructor")
class GLCameraView(
    context: Context,
    onSurfaceReady: (SurfaceTexture) -> Unit,
    onFirstPreviewFrame: () -> Unit = { }
) : GLSurfaceView(context) {

    @Volatile private var renderingActive = false
    @Volatile private var cameraStreamActive = true
    private val released = AtomicBoolean(false)
    private val subjectTracker = SubjectTracker()
    private val trackingExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "HooruSubjectTracker") }
    private val trackingGeneration = AtomicInteger(0)
    private val trackingBusy = AtomicBoolean(false)
    @Volatile private var trackingActive = false
    private var frameTrackingGeneration = 0
    private var trackingListener: (TrackingState) -> Unit = { }
    private val trackingWatchdog = Runnable {
        if (trackingActive) {
            stopObjectTracking()
            trackingListener(TrackingState(lost = true))
        }
    }
    private var analysisListener: (PreviewAnalysis) -> Unit = { }
    val renderer: LutShaderRenderer = LutShaderRenderer(
        context = context.applicationContext,
        onSurfaceReady = onSurfaceReady,
        requestRender = { if (renderingActive) requestRender() },
        onAnalysis = { analysis -> mainHandler.post { analysisListener(analysis) } },
        onFirstCameraFrame = { mainHandler.post(onFirstPreviewFrame) },
        acquireTrackingFrame = {
            if (trackingActive && !released.get() && trackingBusy.compareAndSet(false, true)) {
                frameTrackingGeneration = trackingGeneration.get()
                true
            } else false
        },
        onTrackingFrame = { pixels, width, height, timestamp ->
            val generation = frameTrackingGeneration
            if (released.get()) {
                trackingBusy.set(false)
            } else {
                runCatching {
                    trackingExecutor.execute {
                        try {
                            if (generation == trackingGeneration.get()) {
                                val state = if (pixels.isEmpty()) TrackingState(lost = true)
                                    else subjectTracker.process(pixels, width, height, timestamp)
                                mainHandler.post {
                                    if (generation == trackingGeneration.get() && !released.get()) {
                                        mainHandler.removeCallbacks(trackingWatchdog)
                                        if (state.lost) trackingActive = false
                                        else mainHandler.postDelayed(trackingWatchdog, 600L)
                                        trackingListener(state)
                                    }
                                }
                            }
                        } finally { trackingBusy.set(false) }
                    }
                }.onFailure { trackingBusy.set(false) }
            }
        }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lutExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HooruLutBuilder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val lutPreloadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HooruLutPreloader").apply { priority = Thread.MIN_PRIORITY }
    }
    private val lutCache = ConcurrentHashMap<String, ByteBuffer>()
    private val lutDiskLock = Any()
    private val lutDiskDirectory = File(context.cacheDir, "preset_lut_v1").apply { mkdirs() }
    private val presetGeneration = AtomicInteger(0)
    private val previewWatchdog = object : Runnable {
        override fun run() {
            if (!renderingActive || !cameraStreamActive) return
            renderer.pollFrameIfNeeded()
            mainHandler.postDelayed(this, 250L)
        }
    }
    init {
        setEGLContextClientVersion(3)
        // The same EGL config is reused for MediaRecorder's input surface. The
        // recordable attribute is required by several vendor encoders.
        setEGLConfigChooser { egl: EGL10, display: EGLDisplay ->
            val attributes = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL_RECORDABLE_ANDROID, 1,
                EGL10.EGL_NONE
            )
            val count = IntArray(1)
            check(egl.eglChooseConfig(display, attributes, null, 0, count) && count[0] > 0) {
                "No recordable EGL config is available"
            }
            val configs = arrayOfNulls<EGLConfig>(count[0])
            check(egl.eglChooseConfig(display, attributes, configs, configs.size, count))
            requireNotNull(configs.firstOrNull())
        }
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
        if (cameraStreamActive) mainHandler.postDelayed(previewWatchdog, 250L)
    }

    override fun onPause() {
        if (!renderingActive) return
        renderingActive = false
        stopObjectTracking()
        mainHandler.removeCallbacks(previewWatchdog)
        super.onPause()
    }

    fun applyPreset(preset: Preset) {
        if (released.get()) return
        val generation = presetGeneration.incrementAndGet()
        if (preset.id == "no_filter") {
            queueEvent {
                if (generation == presetGeneration.get()) {
                    renderer.disableLut()
                    renderer.setLookControls(preset.intensity, 0f, preset.halation)
                }
            }
            return
        }

        runCatching { lutExecutor.execute {
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
        } }
    }

    /**
     * Builds the small set of commonly used LUTs without occupying the executor
     * that handles an active user selection. A page turn can therefore always
     * jump ahead of background preparation work.
     */
    fun preloadPresets(presets: List<Preset>) {
        if (released.get()) return
        val uncached = presets.filterNot {
            it.id == "no_filter" || lutCache.containsKey(it.id)
        }
        if (uncached.isEmpty()) return
        runCatching { lutPreloadExecutor.execute {
            uncached.forEach(::prepareLut)
        } }
    }

    private fun prepareLut(preset: Preset): ByteBuffer? {
        lutCache[preset.id]?.let { return it }
        val byteCount = LutShaderRenderer.LUT_SIZE * LutShaderRenderer.LUT_SIZE * LutShaderRenderer.LUT_SIZE * 3
        val diskFile = File(lutDiskDirectory, "${preset.id.hashCode()}_${preset.hashCode()}.rgb")
        val generated = synchronized(lutDiskLock) {
            runCatching {
                context.assets.open("preset_luts/${preset.id}.rgb").use { it.readBytes() }
            }.getOrNull()?.takeIf { it.size == byteCount }?.let { bytes ->
                ByteBuffer.allocateDirect(byteCount).apply { put(bytes); position(0) }
            } ?: diskFile.takeIf { it.length() == byteCount.toLong() }?.readBytes()?.let { bytes ->
                ByteBuffer.allocateDirect(byteCount).apply { put(bytes); position(0) }
            } ?: (preset.lightroom?.buildLut(LutShaderRenderer.LUT_SIZE)
                ?: renderer.buildBuiltInLut(preset.id))?.also { buffer ->
                    runCatching {
                        val bytes = ByteArray(buffer.remaining())
                        buffer.duplicate().apply { position(0) }.get(bytes)
                        val temporary = File(lutDiskDirectory, "${diskFile.name}.tmp")
                        temporary.writeBytes(bytes)
                        if (!temporary.renameTo(diskFile)) {
                            temporary.copyTo(diskFile, overwrite = true)
                            temporary.delete()
                        }
                    }
                }
        } ?: return null
        val cached = generated.asReadOnlyBuffer().apply { position(0) }
        return lutCache.putIfAbsent(preset.id, cached) ?: cached
    }

    fun setAssistSettings(zebraMode: Int, focusPeaking: Boolean) {
        queueEvent { renderer.setAssistSettings(zebraMode, focusPeaking) }
    }

    fun setTrackingListener(listener: (TrackingState) -> Unit) {
        trackingListener = listener
    }

    fun startObjectTracking(x: Float, y: Float) {
        if (released.get() || !renderingActive || !cameraStreamActive) return
        trackingActive = false
        trackingGeneration.incrementAndGet()
        trackingExecutor.execute { subjectTracker.select(x, y) }
        trackingActive = true
        mainHandler.removeCallbacks(trackingWatchdog)
        mainHandler.postDelayed(trackingWatchdog, 600L)
        trackingListener(TrackingState(searching = true))
    }

    fun stopObjectTracking() {
        mainHandler.removeCallbacks(trackingWatchdog)
        trackingActive = false
        trackingGeneration.incrementAndGet()
        if (!released.get()) trackingExecutor.execute { subjectTracker.reset() }
        trackingListener(TrackingState())
    }

    fun setAnalysisListener(listener: (PreviewAnalysis) -> Unit) {
        analysisListener = listener
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        queueEvent { renderer.setAnalysisEnabled(enabled) }
    }

    fun setCameraStreamActive(active: Boolean) {
        cameraStreamActive = active
        if (!active) stopObjectTracking()
        mainHandler.removeCallbacks(previewWatchdog)
        if (active && renderingActive) {
            requestRender()
            mainHandler.postDelayed(previewWatchdog, 250L)
        }
    }

    /** Runs on the GL thread; callers should invoke this from a background dispatcher. */
    fun attachRecordingSurface(surface: Surface, width: Int, height: Int): Result<Unit> {
        if (released.get()) return Result.failure(IllegalStateException("GL camera view is released"))
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<Unit>>()
        queueEvent {
            outcome.set(runCatching { renderer.attachEncoderSurface(surface, width, height) })
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            return Result.failure(IllegalStateException("Timed out while attaching the video surface"))
        }
        return outcome.get() ?: Result.failure(IllegalStateException("Video surface was not attached"))
    }

    fun setRecordingFrames(active: Boolean) {
        if (released.get()) return
        queueEvent { renderer.setRecordingFrames(active) }
    }

    /** Runs on the GL thread; callers should invoke this from a background dispatcher. */
    fun detachRecordingSurface() {
        if (released.get()) return
        val latch = CountDownLatch(1)
        queueEvent {
            renderer.detachEncoderSurface()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        renderingActive = false
        stopObjectTracking()
        trackingExecutor.shutdownNow()
        cameraStreamActive = false
        mainHandler.removeCallbacksAndMessages(null)
        renderer.releaseSurface()
        runCatching { queueEvent {
            renderer.detachEncoderSurface()
            renderer.releaseGlResources()
        } }
        lutExecutor.shutdownNow()
        lutPreloadExecutor.shutdownNow()
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
    onFirstPreviewFrame: () -> Unit = { },
    onPreviewAnalysis: (PreviewAnalysis) -> Unit = { }
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraView by remember { mutableStateOf<GLCameraView?>(null) }
    AndroidView(
        factory = { ctx ->
            GLCameraView(ctx, onSurfaceReady, onFirstPreviewFrame).apply {
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
    if (presetId == "no_filter") return null
    return when (presetId) {
        "silver_push" -> ColorMatrix(floatArrayOf(
            .36f, .72f, .12f, 0f, -22f, .36f, .72f, .12f, 0f, -22f,
            .36f, .72f, .12f, 0f, -22f, 0f, 0f, 0f, 1f, 0f
        ))
        "noir_halide" -> ColorMatrix(floatArrayOf(
            .44f, .87f, .14f, 0f, -48f, .44f, .87f, .14f, 0f, -48f,
            .44f, .87f, .14f, 0f, -48f, 0f, 0f, 0f, 1f, 0f
        ))
        else -> ColorMatrix()
    }
}
