package com.purepixel.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.graphics.ImageFormat
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Gainmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.Image
import android.provider.MediaStore
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.exifinterface.media.ExifInterface
import com.purepixel.camera.gl.createPresetColorMatrix
import com.purepixel.camera.model.LightroomPreset
import com.purepixel.camera.model.NativePresetProcessor
import com.purepixel.camera.model.Preset
import com.purepixel.camera.model.ProcessingMode
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.log2

private const val CameraRelativePath = "DCIM/Camera/"

class CameraEngine(private val context: Context) {
    enum class FlashMode { OFF, AUTO, ON }
    enum class WhiteBalanceMode { AUTO, DAYLIGHT }
    enum class JpegResolutionMode { STANDARD_12_MP, FULL_SENSOR }

    companion object {
        private const val TAG = "HooruCameraEngine"
    }

    data class TelemetryData(
        val iso: Int = 100,
        val shutterSpeed: String = "1/50",
        val exposureTimeNs: Long = 20_000_000L,
        val format: String = "JPG",
        val exposureCompensation: Float = 0f,
        val isoManual: Boolean = false,
        val shutterManual: Boolean = false
    )

    data class ExposureCapabilities(
        val manualSensor: Boolean = false,
        val minimumIso: Int = 50,
        val maximumIso: Int = 6400,
        val minimumExposureTimeNs: Long = 125_000L,
        val maximumExposureTimeNs: Long = 1_000_000_000L,
        val minimumCompensationEv: Float = -3f,
        val maximumCompensationEv: Float = 3f,
        val compensationStepEv: Float = 1f / 3f
    )

    data class GallerySaveState(
        val pendingCount: Int = 0,
        val savedRevision: Int = 0,
        val failedRevision: Int = 0
    )

    data class CaptureTriggerResult(
        val accepted: Boolean,
        val blackoutDurationMs: Long,
        val expectedOutputCount: Int = 0
    )

    data class FocalLengthOption(
        val millimeters: Float,
        val zoomRatio: Float
    )

    data class ZoomState(
        val ratio: Float = 1f,
        val minimumRatio: Float = 1f,
        val maximumRatio: Float = 1f,
        val equivalentFocalLengthMillimeters: Float? = null
    )

    var onTelemetryListener: ((TelemetryData) -> Unit)? = null
    var onRawCapabilityListener: ((Boolean) -> Unit)? = null
    var onFocalLengthsListener: ((List<FocalLengthOption>) -> Unit)? = null
    var onZoomStateListener: ((ZoomState) -> Unit)? = null
    var onExposureCapabilitiesListener: ((ExposureCapabilities) -> Unit)? = null
    var onPreviewConfigurationListener: ((Size) -> Unit)? = null
    var onGallerySaveStateListener: ((GallerySaveState) -> Unit)? = null
    var onHardwareShutterListener: (() -> Unit)? = null
    var onDeviceOrientationListener: ((Int) -> Unit)? = null
    var currentPreviewSize: Size? = null
        private set

    @Volatile
    var supportsRawCapture: Boolean = false
        private set

    @Volatile
    var availableFocalLengths: List<FocalLengthOption> = emptyList()
        private set

    @Volatile
    var exposureCapabilities: ExposureCapabilities = ExposureCapabilities()
        private set

    private var cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var activePreviewSurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingGallerySaves = AtomicInteger(0)
    private val savedGalleryRevision = AtomicInteger(0)
    private val failedGalleryRevision = AtomicInteger(0)
    private val imageProcessingExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "HooruImageProcessing")
    }

    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    private var jpegImageFormat: Int = ImageFormat.JPEG
    private var ultraHdrDisabledForSession: Boolean = false
    private val captureLock = Any()
    private var pendingCapture: PendingCapture? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var lastTelemetryDispatchNanos: Long = 0L
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewWidth: Int = 1920
    private var previewHeight: Int = 1440
    private var desiredLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var activeCameraId: String? = null
    private var desiredCameraId: String? = null
    private var baseEquivalentFocalLength: Float? = null

    private data class CameraProfile(
        val id: String,
        val characteristics: CameraCharacteristics,
        val nativeEquivalentFocalLength: Float,
        val minimumHardwareZoom: Float,
        val maximumHardwareZoom: Float,
        val isLogicalMultiCamera: Boolean,
        val isPrimary: Boolean
    ) {
        fun minimumNormalizedZoom(baseEquivalent: Float): Float = if (isPrimary) {
            minimumHardwareZoom
        } else {
            nativeEquivalentFocalLength / baseEquivalent * minimumHardwareZoom
        }

        fun maximumNormalizedZoom(baseEquivalent: Float): Float = if (isPrimary) {
            maximumHardwareZoom
        } else {
            nativeEquivalentFocalLength / baseEquivalent * maximumHardwareZoom
        }

        fun hardwareZoomFor(normalizedZoom: Float, baseEquivalent: Float): Float = if (isPrimary) {
            normalizedZoom
        } else {
            normalizedZoom * baseEquivalent / nativeEquivalentFocalLength
        }.coerceIn(minimumHardwareZoom, maximumHardwareZoom)
    }

    private var cameraProfiles: List<CameraProfile> = emptyList()

    private var currentIso: Int = 400
    private var currentShutterStr: String = "1/60"
    private var currentExposureTimeNs: Long = 20_000_000L
    private var baseAutoIso: Int = 400
    private var baseAutoExposureTimeNs: Long = 20_000_000L
    private var manualIso: Int? = null
    private var manualExposureTimeNs: Long? = null
    private var exposureCompensationEv: Float = 0f
    // Negative-only offset for looks that lift scene brightness after the sensor
    // exposure has been chosen. It protects highlights without making dark looks
    // brighten the capture or overriding a deliberately manual exposure.
    private var filterAutoExposureBiasEv: Float = 0f
    private var activeFilterId: String = "no_filter"
    private var activeLightroomPreset: LightroomPreset? = null
    private var activeLookIntensity: Float = 1f
    private var activeLookGrain: Float = 0f
    private var activeLookHalation: Float = 0f
    private var activeProcessingMode: ProcessingMode = ProcessingMode.NATURAL
    @Volatile var captureAspectRatio: Float = 3f / 4f
    var captureFormat: String = "JPG" // "JPG" | "RAW" | "RAW+JPG"
        private set
    var jpegResolutionMode: JpegResolutionMode = JpegResolutionMode.STANDARD_12_MP
        private set
    @Volatile
    var currentZoomRatio: Float = 1.0f
        private set
    val currentZoomState: ZoomState
        get() = buildZoomState()
    @Volatile
    private var currentHardwareZoomRatio: Float = 1.0f
    var isFlashEnabled: Boolean = false
        private set
    var flashMode: FlashMode = FlashMode.OFF
        private set
    var whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO
        private set
    private var minimumZoomRatio: Float = 1.0f
    private var maximumZoomRatio: Float = 1.0f
    private var meteringGeneration: Int = 0
    @Volatile private var previewStreamPaused: Boolean = false
    @Volatile private var videoMode: Boolean = false
    @Volatile private var cameraOpening: Boolean = false
    private var cameraGeneration: Int = 0
    // The activity is deliberately portrait-locked, so Display.rotation stays at
    // ROTATION_0 even when the photographer turns the phone. Track the physical
    // orientation separately and snapshot it for every still capture.
    @Volatile
    var deviceOrientationDegrees: Int = 0
        private set

    private data class CaptureSpec(
        val jpegOrientation: Int,
        val aspectRatio: Float,
        val filterId: String,
        val lightroomPreset: LightroomPreset?,
        val intensity: Float,
        val grain: Float,
        val halation: Float,
        val characteristics: CameraCharacteristics?
    )

    private class PendingCapture(
        val spec: CaptureSpec,
        val wantsJpeg: Boolean,
        val wantsRaw: Boolean
    ) {
        var jpegReceived = !wantsJpeg
        var rawDispatched = !wantsRaw
        var rawImage: Image? = null
        var rawResult: TotalCaptureResult? = null
    }
    private val orientationListener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val snappedOrientation = ((orientation + 45) / 90 * 90) % 360
            if (snappedOrientation == deviceOrientationDegrees) return
            deviceOrientationDegrees = snappedOrientation
            mainHandler.post { onDeviceOrientationListener?.invoke(snappedOrientation) }
        }
    }

    private fun updateRawCapability(supported: Boolean) {
        supportsRawCapture = supported
        if (!supported && captureFormat != "JPG") {
            captureFormat = "JPG"
        }
        mainHandler.post { onRawCapabilityListener?.invoke(supported) }
    }

    private fun updateAvailableFocalLengths(options: List<FocalLengthOption>) {
        availableFocalLengths = options
        mainHandler.post { onFocalLengthsListener?.invoke(options) }
    }

    private fun buildZoomState(): ZoomState {
        val base = baseEquivalentFocalLength
        return ZoomState(
            ratio = currentZoomRatio,
            minimumRatio = minimumZoomRatio,
            maximumRatio = maximumZoomRatio,
            equivalentFocalLengthMillimeters = base?.times(currentZoomRatio)
        )
    }

    private fun dispatchZoomState() {
        val state = buildZoomState()
        mainHandler.post { onZoomStateListener?.invoke(state) }
    }

    private fun equivalentFocalLengths(characteristics: CameraCharacteristics): List<Float> {
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorWidth = physicalSize?.width?.takeIf { it.isFinite() && it > 0f } ?: return emptyList()
        return (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf())
            .filter { it.isFinite() && it > 0f }
            .map { actualMillimeters -> actualMillimeters * 36f / sensorWidth }
            .filter { it.isFinite() && it > 0f }
    }

    private fun reportedFocalLengths(characteristics: CameraCharacteristics): List<Float> =
        (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf())
            .filter { it.isFinite() && it > 0f }

    private fun hardwareZoomRange(characteristics: CameraCharacteristics): Pair<Float, Float> {
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
            return range.lower.coerceAtLeast(.01f) to range.upper.coerceAtLeast(range.lower)
        }
        return 1f to (characteristics
            .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?.coerceAtLeast(1f) ?: 1f)
    }

    /**
     * Camera2 vendors expose multi-camera phones in two valid ways: as one logical
     * camera with physical children, or as several independent public camera IDs.
     * Build one topology from both forms so devices such as Motorola's Edge series
     * do not silently lose their ultra-wide lens.
     */
    private fun discoverCameraProfiles(facing: Int): Pair<List<CameraProfile>, Float?> {
        val facingCameras = cameraManager.cameraIdList.mapNotNull { id ->
            runCatching { id to cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }.filter { (_, characteristics) ->
            characteristics.get(CameraCharacteristics.LENS_FACING) == facing
        }
        val previewCameras = facingCameras.filter { (_, characteristics) ->
            runCatching {
                !characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    .isNullOrEmpty()
            }.getOrDefault(false)
        }
        val publicCameras = previewCameras.ifEmpty { facingCameras }
        if (publicCameras.isEmpty()) return emptyList<CameraProfile>() to null

        // Keep Camera2's first camera for this facing as the stable entry point.
        // Some Motorola HALs publish an additional logical alias after their
        // independently usable lenses. That alias advertises output sizes and a
        // zoom range, but rejects every preview + JPEG session at runtime.
        val primaryPair = publicCameras.first()
        val primaryPhysicalEquivalents = primaryPair.second.physicalCameraIds.flatMap { physicalId ->
            runCatching {
                equivalentFocalLengths(cameraManager.getCameraCharacteristics(physicalId))
            }.getOrDefault(emptyList())
        }
        val primaryEquivalents = (
            equivalentFocalLengths(primaryPair.second) + primaryPhysicalEquivalents
        ).distinct()
        // Around 24-28 mm is the conventional Camera2 1x view. Choosing the value
        // nearest 26 mm is stable even when a HAL returns focal lengths unordered.
        val baseEquivalent = primaryEquivalents.minByOrNull { kotlin.math.abs(it - 26f) }
            ?: publicCameras.asSequence()
                .flatMap { equivalentFocalLengths(it.second).asSequence() }
                .minByOrNull { kotlin.math.abs(it - 26f) }
            // Incomplete LEGACY/LIMITED HAL metadata must never prevent the
            // camera from opening. 26 mm is only a display baseline fallback.
            ?: 26f
        val primaryReportedFocalLength = reportedFocalLengths(primaryPair.second)
            .minByOrNull { kotlin.math.abs(it - 5f) }

        val profiles = publicCameras.mapNotNull { (id, characteristics) ->
            val isLogicalMultiCamera = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            if (id != primaryPair.first && isLogicalMultiCamera) {
                Log.w(TAG, "Ignoring secondary logical camera alias $id")
                return@mapNotNull null
            }
            val equivalents = equivalentFocalLengths(characteristics)
            val physicalEquivalents = characteristics.physicalCameraIds.flatMap { physicalId ->
                runCatching {
                    equivalentFocalLengths(cameraManager.getCameraCharacteristics(physicalId))
                }.getOrDefault(emptyList())
            }
            val nativeEquivalent = if (id == primaryPair.first) {
                (equivalents + physicalEquivalents).minByOrNull {
                    kotlin.math.abs(it - baseEquivalent)
                }
            } else {
                equivalents.minByOrNull { kotlin.math.abs(it - baseEquivalent) }
            } ?: run {
                val reported = reportedFocalLengths(characteristics).firstOrNull()
                if (reported != null && primaryReportedFocalLength != null) {
                    baseEquivalent * reported / primaryReportedFocalLength
                } else {
                    baseEquivalent
                }
            }
            val (minimumHardwareZoom, maximumHardwareZoom) = hardwareZoomRange(characteristics)
            CameraProfile(
                id = id,
                characteristics = characteristics,
                nativeEquivalentFocalLength = nativeEquivalent,
                minimumHardwareZoom = minimumHardwareZoom,
                maximumHardwareZoom = maximumHardwareZoom,
                isLogicalMultiCamera = isLogicalMultiCamera,
                isPrimary = id == primaryPair.first
            )
        }
        Log.i(
            TAG,
            "Discovered ${profiles.size} camera profile(s) for facing=$facing: " +
                profiles.joinToString { profile ->
                    "${profile.id}=${profile.nativeEquivalentFocalLength.roundToInt()}mm " +
                        "[${profile.minimumHardwareZoom},${profile.maximumHardwareZoom}]"
                }
        )
        return profiles to baseEquivalent
    }

    private fun fallbackCameraProfile(facing: Int): Pair<List<CameraProfile>, Float?> {
        val selected = cameraManager.cameraIdList.firstNotNullOfOrNull { id ->
            runCatching { id to cameraManager.getCameraCharacteristics(id) }.getOrNull()
                ?.takeIf { (_, characteristics) ->
                    characteristics.get(CameraCharacteristics.LENS_FACING) == facing
                }
        } ?: cameraManager.cameraIdList.firstNotNullOfOrNull { id ->
            runCatching { id to cameraManager.getCameraCharacteristics(id) }.getOrNull()
        } ?: return emptyList<CameraProfile>() to null
        val equivalent = equivalentFocalLengths(selected.second).firstOrNull() ?: 26f
        val (minimumHardwareZoom, maximumHardwareZoom) = runCatching {
            hardwareZoomRange(selected.second)
        }.getOrDefault(1f to 1f)
        return listOf(
            CameraProfile(
                id = selected.first,
                characteristics = selected.second,
                nativeEquivalentFocalLength = equivalent,
                minimumHardwareZoom = minimumHardwareZoom,
                maximumHardwareZoom = maximumHardwareZoom,
                isLogicalMultiCamera = false,
                isPrimary = true
            )
        ) to equivalent
    }

    private fun selectProfileForZoom(normalizedZoom: Float): CameraProfile? {
        val base = baseEquivalentFocalLength ?: return cameraProfiles.firstOrNull()
        val candidates = cameraProfiles.filter { profile ->
            normalizedZoom + .005f >= profile.minimumNormalizedZoom(base) &&
                normalizedZoom - .005f <= profile.maximumNormalizedZoom(base)
        }
        // A logical camera already performs its own seamless physical-lens switch.
        candidates.firstOrNull { it.isPrimary && it.isLogicalMultiCamera }?.let { return it }
        fun zoomCost(profile: CameraProfile): Double =
            kotlin.math.abs(kotlin.math.ln(profile.hardwareZoomFor(normalizedZoom, base).toDouble()))

        val best = candidates.minByOrNull(::zoomCost)
        val active = candidates.firstOrNull { it.id == activeCameraId }
        // A small hysteresis prevents two independent lenses from repeatedly
        // reopening around their exact crossover while the rocker is moving.
        if (active != null && best != null && zoomCost(active) <= zoomCost(best) + kotlin.math.ln(1.2)) {
            return active
        }
        return best ?: cameraProfiles.minByOrNull { profile ->
            val lower = profile.minimumNormalizedZoom(base)
            val upper = profile.maximumNormalizedZoom(base)
            kotlin.math.abs(normalizedZoom - normalizedZoom.coerceIn(lower, upper))
        }
    }

    private fun updateExposureCapabilities(capabilities: ExposureCapabilities) {
        exposureCapabilities = capabilities
        if (!capabilities.manualSensor) {
            manualIso = null
            manualExposureTimeNs = null
        }
        mainHandler.post { onExposureCapabilitiesListener?.invoke(capabilities) }
    }

    @Synchronized
    fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        val thread = HandlerThread("HooruCameraBackground").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    fun stopBackgroundThread() {
        val thread = synchronized(this) {
            val running = backgroundThread
            backgroundThread = null
            backgroundHandler = null
            running
        } ?: return
        thread.quitSafely()
        try {
            // Full-resolution filter processing can still be finishing on this
            // thread. Never block the main thread long enough to trigger an ANR
            // when the user switches apps repeatedly.
            thread.join(350L)
            if (thread.isAlive) Log.i(TAG, "Camera thread will finish pending image work asynchronously")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Error closing background thread", e)
        }
    }

    fun startOrientationTracking() = orientationListener.enable()

    fun stopOrientationTracking() = orientationListener.disable()

    fun setCaptureFormat(format: String) {
        val supported = if (supportsRawCapture) setOf("JPG", "RAW", "RAW+JPG") else setOf("JPG")
        val next = format.takeIf(supported::contains) ?: "JPG"
        if (captureFormat == next) return
        captureFormat = next
        reconfigureCamera()
    }

    fun setJpegResolutionMode(mode: JpegResolutionMode) {
        if (jpegResolutionMode == mode) return
        jpegResolutionMode = mode
        reconfigureCamera()
    }

    private fun reconfigureCamera() {
        val texture = previewSurfaceTexture ?: return
        val width = previewWidth
        val height = previewHeight
        backgroundHandler?.post {
            if (texture.isReleased) return@post
            closeCamera()
            openCamera(texture, width, height)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun openCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (surfaceTexture.isReleased) return
        val cameraHandler = backgroundHandler ?: return
        if (Looper.myLooper() !== cameraHandler.looper) {
            // SurfaceTexture is published by GLSurfaceView's GL thread. Camera
            // discovery, characteristics and ImageReader allocation must not hold
            // that thread up while it finishes the first visible frame.
            cameraHandler.post { openCamera(surfaceTexture, width, height) }
            return
        }
        val surfaceChanged = previewSurfaceTexture !== surfaceTexture
        previewSurfaceTexture = surfaceTexture
        previewWidth = width
        previewHeight = height
        // A recreated GL context owns a new SurfaceTexture. Never leave Camera2
        // attached to the stale texture, and never issue two concurrent open calls.
        if (surfaceChanged && (cameraDevice != null || cameraOpening)) {
            closeCameraLocked()
        }
        if (cameraDevice != null || cameraOpening) return
        cameraOpening = true
        val openGeneration = ++cameraGeneration
        try {
            val discovery = runCatching {
                discoverCameraProfiles(desiredLensFacing)
            }.getOrElse { error ->
                Log.w(TAG, "Camera topology metadata is incomplete; using primary camera", error)
                fallbackCameraProfile(desiredLensFacing)
            }
            val (discoveredProfiles, discoveredBaseEquivalent) = discovery.takeIf {
                it.first.isNotEmpty() && it.second != null
            } ?: fallbackCameraProfile(desiredLensFacing)
            cameraProfiles = discoveredProfiles
            baseEquivalentFocalLength = discoveredBaseEquivalent
            val selectedProfile = desiredCameraId
                ?.let { wanted -> discoveredProfiles.firstOrNull { it.id == wanted } }
                ?: discoveredProfiles.firstOrNull { it.isPrimary }
                ?: discoveredProfiles.firstOrNull()
                ?: error("No usable camera for lens facing $desiredLensFacing")
            val backCameraId = selectedProfile.id
            desiredCameraId = backCameraId
            activeCameraId = backCameraId
            activeCharacteristics = selectedProfile.characteristics
            val map = activeCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val requestCapabilities = activeCharacteristics
                ?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            val isoRange = activeCharacteristics
                ?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = activeCharacteristics
                ?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val compensationRange = activeCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val compensationStep = activeCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.toFloat()
                ?.takeIf { it > 0f }
                ?: 1f / 3f
            updateExposureCapabilities(
                ExposureCapabilities(
                    manualSensor = requestCapabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                    ),
                    minimumIso = isoRange?.lower ?: 50,
                    maximumIso = isoRange?.upper ?: 6400,
                    minimumExposureTimeNs = exposureRange?.lower ?: 125_000L,
                    maximumExposureTimeNs = exposureRange?.upper ?: 1_000_000_000L,
                    minimumCompensationEv = ((compensationRange?.lower ?: -9) * compensationStep)
                        .coerceAtLeast(-3f),
                    maximumCompensationEv = ((compensationRange?.upper ?: 9) * compensationStep)
                        .coerceAtMost(3f),
                    compensationStepEv = compensationStep
                )
            )

            val baseEquivalent = requireNotNull(baseEquivalentFocalLength)
            minimumZoomRatio = discoveredProfiles.minOfOrNull {
                it.minimumNormalizedZoom(baseEquivalent)
            } ?: 1f
            maximumZoomRatio = discoveredProfiles.maxOfOrNull {
                it.maximumNormalizedZoom(baseEquivalent)
            }?.coerceAtMost(8f) ?: 1f
            val requestedNormalizedZoom = currentZoomRatio.coerceIn(minimumZoomRatio, maximumZoomRatio)
            currentZoomRatio = requestedNormalizedZoom
            currentHardwareZoomRatio = selectedProfile.hardwareZoomFor(
                requestedNormalizedZoom,
                baseEquivalent
            )

            val publicEquivalents = discoveredProfiles.map { it.nativeEquivalentFocalLength }
            val physicalEquivalents = discoveredProfiles.flatMap { profile ->
                profile.characteristics.physicalCameraIds.flatMap { physicalId ->
                runCatching {
                    equivalentFocalLengths(cameraManager.getCameraCharacteristics(physicalId))
                }.getOrDefault(emptyList())
                }
            }
            val allEquivalentFocalLengths = (physicalEquivalents + publicEquivalents)
                .filter { it.isFinite() && it > 0f }
                .distinctBy { it.roundToInt() }
                .sorted()
            val focalLengthOptions = allEquivalentFocalLengths.mapNotNull { equivalent ->
                val zoomRatio = equivalent / baseEquivalent
                zoomRatio.takeIf {
                    it + .005f >= minimumZoomRatio && it - .005f <= maximumZoomRatio
                }?.let {
                    FocalLengthOption(
                        millimeters = equivalent,
                        zoomRatio = it
                    )
                }
            }.ifEmpty {
                listOf(FocalLengthOption(baseEquivalent, 1f))
            }
            updateAvailableFocalLengths(focalLengthOptions)
            dispatchZoomState()

            // Setup RAW and JPEG ImageReaders
            val supportsRaw = requestCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            val rawCaptureSupported = supportsRaw && rawSize != null
            updateRawCapability(rawCaptureSupported)
            jpegImageFormat = if (
                captureFormat != "RAW" &&
                !ultraHdrDisabledForSession &&
                !map?.getOutputSizes(ImageFormat.JPEG_R).isNullOrEmpty()
            ) {
                ImageFormat.JPEG_R
            } else {
                ImageFormat.JPEG
            }
            val jpegSize = chooseJpegSize(map, jpegImageFormat)

            rawImageReader = rawSize?.takeIf {
                rawCaptureSupported && (captureFormat == "RAW" || captureFormat == "RAW+JPG")
            }?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            }
            jpegImageReader = if (captureFormat == "JPG" || captureFormat == "RAW+JPG") {
                ImageReader.newInstance(jpegSize.width, jpegSize.height, jpegImageFormat, 2)
            } else null
            rawImageReader?.setOnImageAvailableListener(::handleRawImage, cameraHandler)
            jpegImageReader?.setOnImageAvailableListener(::handleJpegImage, cameraHandler)

            val previewSize = choosePreviewSize(map, width, height)
            currentPreviewSize = previewSize
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            onPreviewConfigurationListener?.invoke(previewSize)
            val previewSurface = Surface(surfaceTexture)
            val captureSurfaces = buildList {
                if (captureFormat == "JPG" || captureFormat == "RAW+JPG") {
                    jpegImageReader?.surface?.let(::add)
                }
                if (captureFormat == "RAW" || captureFormat == "RAW+JPG") {
                    rawImageReader?.surface?.let(::add)
                }
            }

            cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val accepted = synchronized(this@CameraEngine) {
                        if (openGeneration != cameraGeneration ||
                            previewSurfaceTexture !== surfaceTexture ||
                            surfaceTexture.isReleased
                        ) {
                            false
                        } else {
                            cameraDevice = camera
                            cameraOpening = false
                            true
                        }
                    }
                    if (accepted) {
                        createCaptureSession(previewSurface, captureSurfaces, camera, openGeneration)
                    } else {
                        previewSurface.release()
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    synchronized(this@CameraEngine) {
                        if (openGeneration == cameraGeneration) {
                            closeCameraLocked()
                        }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    synchronized(this@CameraEngine) {
                        if (openGeneration == cameraGeneration) {
                            closeCameraLocked()
                        }
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            if (openGeneration == cameraGeneration) closeCameraLocked()
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    @Synchronized
    fun resumeCamera() {
        val texture = previewSurfaceTexture ?: return
        if (!texture.isReleased && cameraDevice == null && !cameraOpening && backgroundHandler != null) {
            openCamera(texture, previewWidth, previewHeight)
        }
    }

    fun toggleCamera() {
        desiredLensFacing = if (desiredLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        desiredCameraId = null
        activeCameraId = null
        cameraProfiles = emptyList()
        baseEquivalentFocalLength = null
        currentZoomRatio = 1f
        currentHardwareZoomRatio = 1f
        ultraHdrDisabledForSession = false
        // The front and back cameras can expose different Camera2 capabilities.
        // Hide RAW immediately while the newly selected camera is reopening.
        updateRawCapability(false)
        updateAvailableFocalLengths(emptyList())
        manualIso = null
        manualExposureTimeNs = null
        exposureCompensationEv = 0f
        updateExposureCapabilities(ExposureCapabilities())
        closeCamera()
        isFlashEnabled = false
        flashMode = FlashMode.OFF
        val texture = previewSurfaceTexture ?: return
        backgroundHandler?.post {
            openCamera(texture, previewWidth, previewHeight)
        }
    }

    fun cycleFlashMode(): FlashMode {
        val hasFlash = activeCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val builder = previewRequestBuilder ?: return FlashMode.OFF
        val session = captureSession ?: return FlashMode.OFF
        if (!hasFlash) {
            isFlashEnabled = false
            flashMode = FlashMode.OFF
            return flashMode
        }
        flashMode = when (flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        isFlashEnabled = flashMode == FlashMode.ON
        return try {
            builder.set(CaptureRequest.FLASH_MODE, if (flashMode == FlashMode.ON) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                if (flashMode == FlashMode.AUTO) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH else CaptureRequest.CONTROL_AE_MODE_ON
            )
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            flashMode
        } catch (e: Exception) {
            isFlashEnabled = false
            flashMode = FlashMode.OFF
            Log.e(TAG, "Unable to toggle flash", e)
            flashMode
        }
    }

    fun setWhiteBalanceMode(mode: WhiteBalanceMode): WhiteBalanceMode {
        whiteBalanceMode = mode
        val builder = previewRequestBuilder ?: return whiteBalanceMode
        val session = captureSession ?: return whiteBalanceMode
        return try {
            applyWhiteBalanceSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            whiteBalanceMode
        } catch (e: Exception) {
            Log.e(TAG, "Unable to set white balance", e)
            whiteBalanceMode
        }
    }

    private fun applyWhiteBalanceSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            when (whiteBalanceMode) {
                WhiteBalanceMode.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                WhiteBalanceMode.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            }
        )
    }

    fun setCaptureFilter(preset: Preset) {
        activeFilterId = preset.id
        activeLightroomPreset = preset.lightroom
        activeLookIntensity = preset.intensity
        activeLookGrain = if (preset.processingMode == ProcessingMode.NATURAL) 0f else preset.grain
        activeLookHalation = preset.halation
        activeProcessingMode = preset.processingMode
        applyProcessingModeToPreview()
        filterAutoExposureBiasEv = automaticFilterExposureBias(preset)
        applyExposureControls()
    }

    fun setCaptureFilter(presetId: String, lightroomPreset: LightroomPreset? = null) {
        activeFilterId = presetId
        activeLightroomPreset = lightroomPreset
        activeLookIntensity = 1f
        activeLookGrain = 0f
        activeLookHalation = 0f
        activeProcessingMode = if (presetId == "no_filter") {
            ProcessingMode.NATURAL
        } else {
            ProcessingMode.HOORU
        }
        applyProcessingModeToPreview()
        filterAutoExposureBiasEv = automaticFilterExposureBias(
            Preset(id = presetId, name = presetId, lightroom = lightroomPreset, intensity = 1f, processingMode = activeProcessingMode)
        )
        applyExposureControls()
    }

    /**
     * Estimates the post-processing light gain in EV before Auto-AE builds its
     * request. Lightroom's Exposure value is already expressed in EV; built-in
     * looks are sampled at a bright neutral tone from their capture color matrix.
     */
    private fun automaticFilterExposureBias(preset: Preset): Float {
        if (preset.processingMode != ProcessingMode.HOORU) return 0f
        preset.lightroom?.let { lightroom ->
            return (-lightroom.exposure * preset.intensity).coerceIn(-2f, 0f)
        }
        val matrix = createPresetColorMatrix(preset.id)?.array ?: return 0f
        val reference = 0.72f * 255f
        fun channel(row: Int): Float = (
            matrix[row] * reference + matrix[row + 1] * reference + matrix[row + 2] * reference + matrix[row + 4]
        ).coerceIn(0f, 255f)
        val outputLuminance = (
            .2126f * channel(0) + .7152f * channel(5) + .0722f * channel(10)
        ).coerceAtLeast(1f)
        val gainEv = log2(outputLuminance / reference)
        // Halation only affects highlights, but reserve a small additional margin
        // where it can otherwise clip a bright look's already-raised whites.
        return (-(gainEv.coerceAtLeast(0f) * preset.intensity) - preset.halation * .15f)
            .coerceIn(-1f, 0f)
    }

    private fun applyProcessingModeToPreview() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            applyImageProcessingPreference(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update processing mode", e)
        }
    }

    fun pausePreviewStream() {
        previewStreamPaused = true
        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to pause preview stream", e)
        }
    }

    fun resumePreviewStream() {
        previewStreamPaused = false
        if (captureSession != null && previewRequestBuilder != null) startPreviewRepeatingRequest()
    }

    fun disableExposureBoosts() {
        manualIso = null
        manualExposureTimeNs = null
        applyExposureControls()
    }

    fun meterAndFocus(normalizedX: Float, normalizedY: Float) {
        backgroundHandler?.post { meterAndFocusOnCameraThread(normalizedX, normalizedY) }
    }

    private fun meterAndFocusOnCameraThread(normalizedX: Float, normalizedY: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        trackingFocusActive = false
        backgroundHandler?.removeCallbacks(trackingFocusTimeout)
        val generation = ++meteringGeneration
        val spot = meteringSpot(normalizedX, normalizedY) ?: return

        val supportsAutoFocus = activeCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true
        try {
            if (supportsAutoFocus) {
                // Release the previous scan/lock before focusing at the new point.
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), null, backgroundHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            if ((activeCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(spot))
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            if (supportsAutoFocus) {
                if ((activeCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(spot))
                }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            startPreviewRepeatingRequest()
        } catch (e: Exception) {
            Log.e(TAG, "Spot metering/focus failed", e)
        } finally {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        }

        backgroundHandler?.postDelayed({
            if (generation == meteringGeneration && captureSession === session &&
                previewRequestBuilder === builder) restoreAverageMetering()
        }, 6000L)
    }

    private fun meteringSpot(normalizedX: Float, normalizedY: Float): MeteringRectangle? {
        val sensor = activeCharacteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        // With CONTROL_ZOOM_RATIO, metering coordinates describe the post-zoom field of view.
        val crop = sensor
        var displayX = normalizedX.coerceIn(0f, 1f)
        val displayY = normalizedY.coerceIn(0f, 1f)
        if (activeCharacteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        ) displayX = 1f - displayX

        val sensorOrientation = activeCharacteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val mapped = when (sensorOrientation) {
            90 -> displayY to (1f - displayX)
            180 -> (1f - displayX) to (1f - displayY)
            270 -> (1f - displayY) to displayX
            else -> displayX to displayY
        }
        val centerX = crop.left + (mapped.first * crop.width()).toInt()
        val centerY = crop.top + (mapped.second * crop.height()).toInt()
        val halfWidth = (crop.width() * 0.055f).toInt().coerceAtLeast(1)
        val halfHeight = (crop.height() * 0.055f).toInt().coerceAtLeast(1)
        val spotRect = Rect(
            (centerX - halfWidth).coerceIn(crop.left, crop.right - 1),
            (centerY - halfHeight).coerceIn(crop.top, crop.bottom - 1),
            (centerX + halfWidth).coerceIn(crop.left + 1, crop.right),
            (centerY + halfHeight).coerceIn(crop.top + 1, crop.bottom)
        )
        return MeteringRectangle(spotRect, MeteringRectangle.METERING_WEIGHT_MAX)

    }

    private var trackingFocusActive = false
    private var lastTrackingFocusNanos = 0L
    private val trackingFocusTimeout = Runnable {
        if (trackingFocusActive) {
            trackingFocusActive = false
            restoreAverageMetering()
        }
    }

    /** Follow a visual target with continuous AF; do not lock the lens after each frame. */
    fun updateTrackedFocus(x: Float, y: Float) {
        val expectedCamera = cameraGeneration
        backgroundHandler?.post {
            if (expectedCamera != cameraGeneration || previewStreamPaused) return@post
            val builder = previewRequestBuilder ?: return@post
            val session = captureSession ?: return@post
            val spot = meteringSpot(x, y) ?: return@post
            val modes = activeCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val preferred = if (videoMode) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            val mode = listOf(preferred, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_AUTO)
                .firstOrNull { modes.contains(it) }
            val now = System.nanoTime()
            backgroundHandler?.removeCallbacks(trackingFocusTimeout)
            backgroundHandler?.postDelayed(trackingFocusTimeout, 1000L)
            val interval = if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO) 800_000_000L else 200_000_000L
            if (trackingFocusActive && now - lastTrackingFocusNanos < interval) return@post
            try {
                if (!trackingFocusActive) {
                    ++meteringGeneration // The tap's six-second reset must not interrupt tracking.
                    if (mode != null) {
                        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        session.capture(builder.build(), null, backgroundHandler)
                        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
                    }
                }
                if ((activeCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(spot))
                }
                if ((activeCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(spot))
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                }
                if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                    session.capture(builder.build(), null, backgroundHandler)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    session.capture(builder.build(), null, backgroundHandler)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }
                startPreviewRepeatingRequest()
                trackingFocusActive = true
                lastTrackingFocusNanos = now
            } catch (e: Exception) {
                Log.w(TAG, "Unable to follow subject focus", e)
            } finally {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
        }
    }

    fun stopTrackedFocus() {
        backgroundHandler?.post {
            backgroundHandler?.removeCallbacks(trackingFocusTimeout)
            if (trackingFocusActive) {
                trackingFocusActive = false
                restoreAverageMetering()
            }
        }
    }

    private fun restoreAverageMetering() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            applyCaptureModeSettings(builder)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            applyAverageMetering(builder)
            if (!previewStreamPaused) startPreviewRepeatingRequest()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to restore average metering", e)
        }
    }

    private fun applyAverageMetering(builder: CaptureRequest.Builder) {
        val chars = activeCharacteristics ?: return
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxRegions <= 0) return
        val marginX = (sensor.width() * 0.16f).toInt()
        val marginY = (sensor.height() * 0.16f).toInt()
        val center = MeteringRectangle(
            Rect(
                sensor.left + marginX,
                sensor.top + marginY,
                sensor.right - marginX,
                sensor.bottom - marginY
            ),
            700
        )
        val regions = if (maxRegions >= 2) {
            arrayOf(MeteringRectangle(sensor, 220), center)
        } else {
            arrayOf(center)
        }
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
    }

    private fun cropRectForZoom(sensor: Rect, zoomRatio: Float): Rect {
        val zoom = zoomRatio.coerceAtLeast(1f)
        val width = (sensor.width() / zoom).toInt()
        val height = (sensor.height() / zoom).toInt()
        val left = sensor.left + (sensor.width() - width) / 2
        val top = sensor.top + (sensor.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    fun setManualIso(iso: Int?) {
        manualIso = iso?.coerceIn(
            exposureCapabilities.minimumIso,
            exposureCapabilities.maximumIso
        )?.takeIf { exposureCapabilities.manualSensor }
        applyExposureControls()
    }

    fun setManualExposureTime(exposureTimeNs: Long?) {
        manualExposureTimeNs = exposureTimeNs?.coerceIn(
            exposureCapabilities.minimumExposureTimeNs,
            exposureCapabilities.maximumExposureTimeNs
        )?.takeIf { exposureCapabilities.manualSensor }
        applyExposureControls()
    }

    fun setExposureCompensation(ev: Float) {
        exposureCompensationEv = ev.coerceIn(
            maxOf(-3f, exposureCapabilities.minimumCompensationEv),
            minOf(3f, exposureCapabilities.maximumCompensationEv)
        )
        applyExposureControls()
    }

    fun resetExposureCompensation() = setExposureCompensation(0f)

    private fun applyExposureControls() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            applyExposureSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to apply exposure controls", e)
        }
    }

    private fun applyExposureSettings(builder: CaptureRequest.Builder) {
        val manualActive = exposureCapabilities.manualSensor &&
            (manualIso != null || manualExposureTimeNs != null)
        if (!manualActive) {
            val step = exposureCapabilities.compensationStepEv.takeIf { it > 0f } ?: 1f / 3f
            val compensationRange = activeCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                ((exposureCompensationEv + filterAutoExposureBiasEv) / step).roundToInt().coerceIn(
                    compensationRange?.lower ?: 0,
                    compensationRange?.upper ?: 0
                )
            )
            return
        }
        val evFactor = 2.0.pow(exposureCompensationEv.toDouble())
        var iso = manualIso ?: baseAutoIso
        var exposure = manualExposureTimeNs ?: baseAutoExposureTimeNs
        if (manualIso == null) {
            iso = (iso * evFactor).roundToInt()
        } else {
            exposure = (exposure * evFactor).toLong()
        }
        iso = iso.coerceIn(exposureCapabilities.minimumIso, exposureCapabilities.maximumIso)
        exposure = exposure.coerceIn(
            exposureCapabilities.minimumExposureTimeNs,
            exposureCapabilities.maximumExposureTimeNs
        )
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        val maximumFrameDuration = activeCharacteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: exposure
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            exposure.coerceAtMost(maximumFrameDuration)
        )
    }

    fun openNativeGallery(): Boolean {
        try {
            val latest = findLatestHooruImageUri() ?: return false
            val googlePhotosIntent = Intent(Intent.ACTION_VIEW, latest).apply {
                setDataAndType(latest, "image/*")
                setPackage("com.google.android.apps.photos")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val reviewIntent = Intent("com.android.camera.action.REVIEW", latest).apply {
                setDataAndType(latest, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val intent = when {
                googlePhotosIntent.resolveActivity(context.packageManager) != null -> googlePhotosIntent
                reviewIntent.resolveActivity(context.packageManager) != null -> reviewIntent
                else -> {
                Intent(Intent.ACTION_VIEW, latest).apply {
                    setDataAndType(latest, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                }
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open native gallery", e)
            return false
        }
    }

    fun loadLatestGalleryThumbnail(sizePx: Int = 160): Bitmap? {
        val latest = findLatestHooruImageUri() ?: return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, latest)) { decoder, info, _ ->
                val sourceSize = info.size
                val scale = minOf(
                    sizePx.toFloat() / sourceSize.width.coerceAtLeast(1),
                    sizePx.toFloat() / sourceSize.height.coerceAtLeast(1)
                ).coerceAtMost(1f)
                decoder.setTargetSize(
                    (sourceSize.width * scale).roundToInt().coerceAtLeast(1),
                    (sourceSize.height * scale).roundToInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrElse {
            Log.w(TAG, "Unable to load latest gallery thumbnail", it)
            null
        }
    }

    private fun findLatestHooruImageUri(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = buildString {
            append("${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?")
            append(" AND ${MediaStore.Images.Media.SIZE} > 0")
            append(" AND ${MediaStore.Images.Media.IS_PENDING} = 0")
            append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?")
        }
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            selection,
            arrayOf("hooru_%", CameraRelativePath),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
            }
        }
    }

    private fun createCaptureSession(
        previewSurface: Surface,
        captureSurfaces: List<Surface>,
        device: CameraDevice,
        generation: Int
    ) {
        try {
            val handler = synchronized(this) {
                if (generation != cameraGeneration || cameraDevice !== device) null else backgroundHandler
            } ?: run {
                previewSurface.release()
                return
            }
            // Keeping the preview session small is essential: a maximum-size RAW + JPEG +
            // preview combination is not guaranteed and caused onConfigureFailed on devices.
            val surfaces = listOf(previewSurface) + captureSurfaces

            val requestBuilder = device.createCaptureRequest(
                if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)

                applyImageProcessingPreference(this)
                applyCaptureModeSettings(this)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyWhiteBalanceSettings(this)
                applyAverageMetering(this)
                applyZoomToRequest(this, currentHardwareZoomRatio)
            }

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val accepted = synchronized(this@CameraEngine) {
                        if (generation != cameraGeneration || cameraDevice !== device) {
                            false
                        } else {
                            captureSession = session
                            previewRequestBuilder = requestBuilder
                            activePreviewSurface = previewSurface
                            true
                        }
                    }
                    if (!accepted) {
                        session.close()
                        previewSurface.release()
                    } else if (!previewStreamPaused) {
                        startPreviewRepeatingRequest()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    previewSurface.release()
                    if (jpegImageFormat == ImageFormat.JPEG_R) {
                        Log.w(TAG, "Ultra HDR session unsupported; falling back to SDR JPEG")
                        ultraHdrDisabledForSession = true
                        val texture = previewSurfaceTexture
                        synchronized(this@CameraEngine) { closeCameraLocked() }
                        if (texture != null && !texture.isReleased) {
                            handler.post { openCamera(texture, previewWidth, previewHeight) }
                        }
                    } else if (captureFormat != "JPG") {
                        Log.w(TAG, "$captureFormat session unsupported; falling back to JPG")
                        captureFormat = "JPG"
                        val texture = previewSurfaceTexture
                        synchronized(this@CameraEngine) { closeCameraLocked() }
                        if (texture != null && !texture.isReleased) {
                            handler.post { openCamera(texture, previewWidth, previewHeight) }
                        }
                    } else {
                        Log.e(TAG, "Camera capture session configuration failed")
                    }
                }
            }
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map(::OutputConfiguration),
                { command -> handler.post(command) },
                stateCallback
            )
            device.createCaptureSession(sessionConfiguration)
        } catch (e: Exception) {
            previewSurface.release()
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    fun setVideoMode(enabled: Boolean) {
        videoMode = enabled
        val handler = backgroundHandler ?: return
        handler.post {
            val device = cameraDevice ?: return@post
            val surface = activePreviewSurface ?: return@post
            val session = captureSession ?: return@post
            runCatching {
                val builder = device.createCaptureRequest(
                    if (enabled) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                ).apply {
                    addTarget(surface)
                    applyImageProcessingPreference(this)
                    applyCaptureModeSettings(this)
                    applyExposureSettings(this)
                    applyWhiteBalanceSettings(this)
                    applyAverageMetering(this)
                    applyZoomToRequest(this, currentHardwareZoomRatio)
                }
                previewRequestBuilder = builder
                if (!previewStreamPaused && captureSession === session) startPreviewRepeatingRequest()
            }.onFailure { Log.e(TAG, "Unable to switch capture mode", it) }
        }
    }

    private fun applyCaptureModeSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (videoMode) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        builder.set(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            if (videoMode) CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            else CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
        )
        activeCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.let { ranges ->
                val selected = if (videoMode) {
                    ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                        ?: ranges.filter { it.upper <= 30 }.maxByOrNull { it.lower }
                } else {
                    ranges.filter { it.upper <= 30 }
                        .maxWithOrNull(compareBy<android.util.Range<Int>> { it.upper }.thenBy { -it.lower })
                }
                selected?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
        if (videoMode && activeCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        ) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }
    }

    @Volatile private var pendingZoomRatio: Float? = null
    @Volatile private var zoomUpdateQueued = false
    @Volatile private var lensSwitchQueued = false

    /**
     * Coalesce fast touch updates onto Camera2's handler. This keeps binder work out
     * of Compose's input path while always applying the most recent finger position.
     */
    fun setZoomRatio(zoomRatio: Float) {
        val supportedZoom = zoomRatio
            .takeIf(Float::isFinite)
            ?.coerceIn(minimumZoomRatio, maximumZoomRatio)
            ?: currentZoomRatio
        val profile = selectProfileForZoom(supportedZoom) ?: return
        val base = baseEquivalentFocalLength ?: return
        if (profile.id != activeCameraId) {
            val switchAlreadyRequested = desiredCameraId == profile.id
            currentZoomRatio = supportedZoom
            desiredCameraId = profile.id
            currentHardwareZoomRatio = profile.hardwareZoomFor(supportedZoom, base)
            dispatchZoomState()
            if (!switchAlreadyRequested && !lensSwitchQueued) {
                lensSwitchQueued = true
                val texture = previewSurfaceTexture
                backgroundHandler?.post {
                    lensSwitchQueued = false
                    if (texture == null || texture.isReleased) return@post
                    if (desiredCameraId == activeCameraId) return@post
                    closeCamera()
                    openCamera(texture, previewWidth, previewHeight)
                } ?: run { lensSwitchQueued = false }
            }
            return
        }
        desiredCameraId = profile.id
        val hardwareZoom = profile.hardwareZoomFor(supportedZoom, base)
        currentZoomRatio = supportedZoom
        currentHardwareZoomRatio = hardwareZoom
        pendingZoomRatio = hardwareZoom
        if (zoomUpdateQueued) return
        zoomUpdateQueued = true
        backgroundHandler?.post {
            while (true) {
                val nextZoom = pendingZoomRatio ?: break
                pendingZoomRatio = null
                applyZoomRatio(nextZoom)
                if (pendingZoomRatio == null) break
            }
            zoomUpdateQueued = false
            // Cover a new gesture update that arrived while the queue flag was reset.
            pendingZoomRatio?.let { pendingHardwareZoom ->
                pendingZoomRatio = null
                applyZoomRatio(pendingHardwareZoom)
            }
        } ?: run { zoomUpdateQueued = false }
        dispatchZoomState()
    }

    private fun applyZoomRatio(supportedZoom: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            applyZoomToRequest(builder, supportedZoom)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting zoom ratio", e)
        }
    }

    private fun choosePreviewSize(map: StreamConfigurationMap?, requestedWidth: Int, requestedHeight: Int): Size {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        // Match the 720p GL backing surface instead of feeding it a larger camera
        // stream that must immediately be downsampled. Full-resolution capture is
        // independently provided by the JPEG/RAW ImageReaders.
        val bounded = sizes.filter { it.width <= 960 && it.height <= 960 }
        val fourThree = bounded.filter {
            kotlin.math.abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f
        }
        return (fourThree.ifEmpty { bounded })
            .minByOrNull { size ->
                val ratioError = kotlin.math.abs(size.width.toFloat() / size.height - 4f / 3f)
                val dimensionError = kotlin.math.abs(size.width - requestedWidth) +
                    kotlin.math.abs(size.height - requestedHeight)
                ratioError * 10000f + dimensionError
            }
            ?: sizes.firstOrNull()
            ?: Size(requestedWidth, requestedHeight)
    }

    private fun chooseJpegSize(map: StreamConfigurationMap?, imageFormat: Int): Size {
        val sizes = map?.getOutputSizes(imageFormat).orEmpty()
        if (sizes.isEmpty()) return Size(4000, 3000)
        val maximum = sizes.maxByOrNull { it.width.toLong() * it.height } ?: return Size(4000, 3000)
        if (jpegResolutionMode == JpegResolutionMode.FULL_SENSOR) return maximum

        val targetPixels = 12_000_000L
        val maximumRatio = maximum.width.toFloat() / maximum.height.coerceAtLeast(1)
        val sameAspect = sizes.filter {
            kotlin.math.abs(it.width.toFloat() / it.height.coerceAtLeast(1) - maximumRatio) < .025f
        }
        return sameAspect.ifEmpty { sizes.asList() }.minByOrNull { size ->
            kotlin.math.abs(size.width.toLong() * size.height - targetPixels)
        } ?: maximum
    }

    private fun disableOptionalImageProcessing(builder: CaptureRequest.Builder) {
        val chars = activeCharacteristics ?: return

        if (chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.contains(CaptureRequest.EDGE_MODE_OFF) == true
        ) builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)

        if (chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true
        ) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)

        if (chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
                ?.contains(CaptureRequest.SHADING_MODE_OFF) == true
        ) builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)

        if (chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                ?.contains(CaptureRequest.HOT_PIXEL_MODE_OFF) == true
        ) builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)

        if (chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                ?.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF) == true
        ) builder.set(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
        )

        if (chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) == true
        ) builder.set(
            CaptureRequest.DISTORTION_CORRECTION_MODE,
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
        )
    }

    private fun applyImageProcessingPreference(builder: CaptureRequest.Builder) {
        disableOptionalImageProcessing(builder)
    }

    private fun startPreviewRepeatingRequest() {
        try {
            val builder = previewRequestBuilder ?: return
            captureSession?.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val confirmedHardwareZoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                    if (
                        confirmedHardwareZoom?.isFinite() == true &&
                        captureSession === session &&
                        desiredCameraId == activeCameraId
                    ) {
                        val profile = cameraProfiles.firstOrNull { it.id == activeCameraId }
                        val base = baseEquivalentFocalLength
                        if (profile != null && base != null) {
                            currentHardwareZoomRatio = confirmedHardwareZoom
                            val confirmedNormalizedZoom = if (profile.isPrimary) {
                                confirmedHardwareZoom
                            } else {
                                profile.nativeEquivalentFocalLength / base * confirmedHardwareZoom
                            }.coerceIn(minimumZoomRatio, maximumZoomRatio)
                            if (kotlin.math.abs(confirmedNormalizedZoom - currentZoomRatio) > .005f) {
                                currentZoomRatio = confirmedNormalizedZoom
                                dispatchZoomState()
                            }
                        }
                    }
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    val expTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 20000000L

                    currentIso = iso
                    currentExposureTimeNs = expTimeNs
                    if (manualIso == null && manualExposureTimeNs == null) {
                        baseAutoIso = iso
                        baseAutoExposureTimeNs = expTimeNs
                    }
                    val now = System.nanoTime()
                    if (now - lastTelemetryDispatchNanos >= 250_000_000L) {
                        lastTelemetryDispatchNanos = now
                        currentShutterStr = formatExposureTime(expTimeNs)
                        val telemetry = TelemetryData(
                            iso = currentIso,
                            shutterSpeed = currentShutterStr,
                            exposureTimeNs = currentExposureTimeNs,
                            format = captureFormat,
                            exposureCompensation = exposureCompensationEv,
                            isoManual = manualIso != null,
                            shutterManual = manualExposureTimeNs != null
                        )
                        mainHandler.post { onTelemetryListener?.invoke(telemetry) }
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview request", e)
        }
    }

    private fun formatExposureTime(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            val denominator = Math.round(1.0 / seconds)
            "1/$denominator"
        }
    }

    private fun handleJpegImage(reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val pending = synchronized(captureLock) {
            pendingCapture?.takeIf { it.wantsJpeg && !it.jpegReceived }
        }
        if (pending == null) {
            image.close()
            return
        }
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also(buffer::get)
        } catch (t: Throwable) {
            failPendingCapture(pending)
            Log.w(TAG, "JPEG capture was interrupted", t)
            return
        } finally {
            image.close()
        }
        synchronized(captureLock) {
            if (pendingCapture !== pending) return
            pending.jpegReceived = true
            finishPendingCaptureIfDelivered(pending)
        }
        beginGallerySave()
        submitImageWork {
            val spec = pending.spec
            saveCapturedJpeg(
                bytes,
                spec.aspectRatio,
                spec.filterId,
                spec.lightroomPreset,
                spec.intensity,
                spec.grain,
                spec.halation
            )
        }
    }

    private fun handleRawImage(reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val pending = synchronized(captureLock) {
            val active = pendingCapture?.takeIf { it.wantsRaw && !it.rawDispatched }
            if (active == null) {
                null
            } else {
                active.rawImage?.close()
                active.rawImage = image
                active
            }
        }
        if (pending == null) {
            image.close()
            return
        }
        dispatchRawIfReady(pending)
    }

    private fun dispatchRawIfReady(pending: PendingCapture) {
        val pair = synchronized(captureLock) {
            if (pendingCapture !== pending || pending.rawDispatched) return
            val image = pending.rawImage ?: return
            val result = pending.rawResult ?: return
            pending.rawImage = null
            pending.rawDispatched = true
            finishPendingCaptureIfDelivered(pending)
            image to result
        }
        val characteristics = pending.spec.characteristics
        if (characteristics == null) {
            pair.first.close()
            failedGalleryRevision.incrementAndGet()
            dispatchGallerySaveState()
            return
        }
        beginGallerySave()
        submitImageWork(onRejected = pair.first::close) {
            try {
                saveCapturedRaw(
                    characteristics,
                    pair.second,
                    pair.first,
                    pending.spec.jpegOrientation
                )
            } finally {
                pair.first.close()
            }
        }
    }

    private fun submitImageWork(onRejected: () -> Unit = {}, work: () -> Boolean) {
        if (imageProcessingExecutor.isShutdown) {
            onRejected()
            finishGallerySave(false)
            return
        }
        runCatching {
            imageProcessingExecutor.execute {
                val saved = runCatching(work).getOrElse {
                    Log.e(TAG, "Image processing failed", it)
                    false
                }
                finishGallerySave(saved)
            }
        }.onFailure {
            Log.w(TAG, "Image processor rejected capture", it)
            onRejected()
            finishGallerySave(false)
        }
    }

    private fun finishPendingCaptureIfDelivered(pending: PendingCapture) {
        if (pendingCapture === pending && pending.jpegReceived && pending.rawDispatched) {
            pendingCapture = null
        }
    }

    private fun failPendingCapture(pending: PendingCapture) {
        val failedOutputs = synchronized(captureLock) {
            if (pendingCapture !== pending) return
            val count = (if (pending.jpegReceived) 0 else 1) +
                (if (pending.rawDispatched) 0 else 1)
            pending.rawImage?.close()
            pending.rawImage = null
            pendingCapture = null
            count
        }
        if (failedOutputs > 0) {
            failedGalleryRevision.addAndGet(failedOutputs)
            dispatchGallerySaveState()
        }
    }

    fun triggerCapture(): CaptureTriggerResult {
        val blackoutDuration = (currentExposureTimeNs / 1_000_000L + 45L).coerceIn(65L, 1500L)
        synchronized(captureLock) {
            if (pendingCapture != null || pendingGallerySaves.get() >= 2) {
                return CaptureTriggerResult(false, blackoutDuration)
            }
        }
        val device = cameraDevice ?: return CaptureTriggerResult(false, blackoutDuration)
        val session = captureSession ?: return CaptureTriggerResult(false, blackoutDuration)

        // Keep the capture path safe if a camera capability changes while the
        // preview is being recreated or the lens is being switched.
        if (!supportsRawCapture && captureFormat != "JPG") {
            captureFormat = "JPG"
        }

        var expectedOutputCount = 0
        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val targets = mutableListOf<Surface>()
            val wantsRaw = (captureFormat == "RAW" || captureFormat == "RAW+JPG") && rawImageReader != null
            val wantsJpeg = captureFormat == "JPG" || captureFormat == "RAW+JPG"

            if (wantsRaw) {
                rawImageReader?.surface?.let {
                    captureBuilder.addTarget(it)
                    targets.add(it)
                }
            }

            if (wantsJpeg) {
                jpegImageReader?.surface?.let {
                    captureBuilder.addTarget(it)
                    targets.add(it)
                }
            }
            if (targets.isEmpty()) return CaptureTriggerResult(false, blackoutDuration)
            expectedOutputCount = targets.size

            applyImageProcessingPreference(captureBuilder)
            // Keep the viewfinder's selected focus and metering point during capture.
            previewRequestBuilder?.let { preview ->
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, preview.get(CaptureRequest.CONTROL_AF_MODE))
                captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, preview.get(CaptureRequest.CONTROL_AF_REGIONS))
                captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, preview.get(CaptureRequest.CONTROL_AE_REGIONS))
            }
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            applyExposureSettings(captureBuilder)
            applyWhiteBalanceSettings(captureBuilder)
            applyZoomToRequest(captureBuilder, currentHardwareZoomRatio)
            val jpegOrientation = calculateJpegOrientation()
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            val pending = PendingCapture(
                spec = CaptureSpec(
                    jpegOrientation = jpegOrientation,
                    aspectRatio = captureAspectRatio,
                    filterId = activeFilterId,
                    lightroomPreset = activeLightroomPreset,
                    intensity = activeLookIntensity,
                    grain = activeLookGrain,
                    halation = activeLookHalation,
                    characteristics = activeCharacteristics
                ),
                wantsJpeg = wantsJpeg,
                wantsRaw = wantsRaw
            )
            synchronized(captureLock) {
                if (pendingCapture != null) return CaptureTriggerResult(false, blackoutDuration)
                pendingCapture = pending
            }
            when (flashMode) {
                FlashMode.ON -> captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                FlashMode.AUTO -> captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                FlashMode.OFF -> Unit
            }

            if (targets.isNotEmpty()) {
                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    private fun restorePreview() {
                        if (captureSession === session && !previewStreamPaused) startPreviewRepeatingRequest()
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (wantsRaw) {
                            synchronized(captureLock) {
                                if (pendingCapture === pending) pending.rawResult = result
                            }
                            dispatchRawIfReady(pending)
                        }
                        restorePreview()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        failPendingCapture(pending)
                        restorePreview()
                    }
                }, backgroundHandler)
            }
        } catch (e: Exception) {
            synchronized(captureLock) {
                pendingCapture?.rawImage?.close()
                pendingCapture = null
            }
            Log.e(TAG, "Failed to capture image", e)
            return CaptureTriggerResult(false, blackoutDuration)
        }
        return CaptureTriggerResult(true, blackoutDuration, expectedOutputCount)
    }

    fun requestHardwareShutter() {
        mainHandler.post {
            onHardwareShutterListener?.invoke() ?: triggerCapture()
        }
    }

    private fun saveCapturedJpeg(
        bytes: ByteArray,
        aspectRatio: Float,
        filterId: String,
        lightroomPreset: LightroomPreset?,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Boolean {
        NativePresetProcessor.configureForDevice(context)
        val displayName = "hooru_${System.currentTimeMillis()}_${System.nanoTime() % 1000}.jpg"
        var pendingUri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, CameraRelativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            pendingUri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("MediaStore entry could not be created")
            context.contentResolver.openOutputStream(requireNotNull(pendingUri), "w")?.use { out ->
                writeCapturedJpeg(
                    out,
                    bytes,
                    aspectRatio,
                    filterId,
                    lightroomPreset,
                    intensity,
                    grain,
                    halation
                )
            } ?: error("MediaStore output stream could not be opened")
            val published = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            check(context.contentResolver.update(requireNotNull(pendingUri), published, null, null) > 0) {
                "MediaStore entry could not be published"
            }
            Log.i(TAG, "JPEG published: $pendingUri")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to finish JPEG capture", t)
            pendingUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            false
        }
    }

    private fun saveCapturedRaw(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: android.media.Image,
        orientationDegrees: Int
    ): Boolean {
        val displayName = "hooru_${System.currentTimeMillis()}_${System.nanoTime() % 1000}.dng"
        var pendingUri: Uri? = null
        return try {
            DngCreator(characteristics, result).use { creator ->
                creator.setOrientation(
                    when (orientationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                )
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, CameraRelativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                pendingUri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: error("RAW MediaStore entry could not be created")
                context.contentResolver.openOutputStream(requireNotNull(pendingUri), "w")?.use { out ->
                    creator.writeImage(out, image)
                } ?: error("RAW MediaStore output stream could not be opened")
                val published = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                check(context.contentResolver.update(requireNotNull(pendingUri), published, null, null) > 0) {
                    "RAW MediaStore entry could not be published"
                }
                Log.i(TAG, "RAW DNG published: $pendingUri")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to finish RAW capture", t)
            pendingUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            false
        }
    }

    private fun writeCapturedJpeg(
        output: OutputStream,
        bytes: ByteArray,
        aspectRatio: Float,
        filterId: String,
        lightroomPreset: LightroomPreset?,
        intensity: Float,
        grain: Float,
        halation: Float
    ) {
        var working: Bitmap? = null
        var filtered: Bitmap? = null
        var finished: Bitmap? = null
        var lookAlreadyFinished = false
        try {
            val colorMatrix = createPresetColorMatrix(filterId)
            val requestedAspectRatio = aspectRatio.coerceIn(9f / 21f, 1f)
            val usesNativeAspectRatio = kotlin.math.abs(requestedAspectRatio - 3f / 4f) < .001f
            if (
                usesNativeAspectRatio &&
                colorMatrix == null &&
                lightroomPreset == null &&
                grain <= 0f &&
                halation <= 0f
            ) {
                output.write(bytes)
            } else {
                working = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Camera JPEG could not be decoded")
                val source = requireNotNull(working)
                // Camera2 fulfills JPEG_ORIENTATION either by writing EXIF or by
                // rotating the encoded pixels. No EXIF rotation therefore means
                // the pixels are already upright; applying the current sensor
                // orientation here would double-rotate such captures and would
                // also introduce a race if the phone moves after shutter release.
                val rotationDegrees = jpegExifRotationDegrees(bytes)
                var preservedGainmap = if (source.hasGainmap()) {
                    rotateGainmapForOutput(
                        gainmap = requireNotNull(source.gainmap),
                        rotationDegrees = rotationDegrees
                    )
                } else null
                val oriented = orientBitmap(
                    source = source,
                    rotationDegrees = rotationDegrees
                )
                if (oriented !== source) {
                    source.recycle()
                    working = oriented
                }
                val cropped = centerCropToAspect(
                    source = requireNotNull(working),
                    portraitAspectRatio = requestedAspectRatio
                )
                if (cropped !== working) {
                    requireNotNull(working).recycle()
                    working = cropped
                    preservedGainmap = preservedGainmap?.let { gainmap ->
                        cropGainmapToAspect(gainmap, requestedAspectRatio)
                    }
                }
                filtered = if (lightroomPreset != null) {
                    lookAlreadyFinished = true
                    lightroomPreset.applyToBitmap(
                        source = requireNotNull(working),
                        intensity = intensity,
                        extraGrain = grain,
                        halation = halation
                    )
                } else if (colorMatrix != null) Bitmap.createBitmap(
                    requireNotNull(working).width,
                    requireNotNull(working).height,
                    Bitmap.Config.ARGB_8888
                ).also { result ->
                    lookAlreadyFinished = NativePresetProcessor.processColorMatrix(
                        source = requireNotNull(working),
                        destination = result,
                        matrix = requireNotNull(colorMatrix).array,
                        intensity = intensity,
                        grain = grain,
                        halation = halation
                    )
                    if (!lookAlreadyFinished) {
                        Canvas(result).drawBitmap(requireNotNull(working), 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            colorFilter = ColorMatrixColorFilter(requireNotNull(colorMatrix))
                        })
                    }
                } else requireNotNull(working)
                finished = if (lookAlreadyFinished) {
                    filtered
                } else {
                    finishLook(
                        source = requireNotNull(working),
                        filtered = requireNotNull(filtered),
                        intensity = intensity,
                        grain = grain,
                        halation = halation
                    )
                }
                if (preservedGainmap != null) {
                    requireNotNull(finished).gainmap = preservedGainmap
                }
                check(requireNotNull(finished).compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "JPEG encoder failed"
                }
            }
        } finally {
            finished?.takeIf { it !== filtered && it !== working && !it.isRecycled }?.recycle()
            filtered?.takeIf { it !== working && !it.isRecycled }?.recycle()
            working?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    /**
     * Ultra HDR stores its extra luminance information in a lower-resolution gain map.
     * Pixel looks change only the SDR base image, while spatial operations must be
     * mirrored on the gain map so Android can encode a valid JPEG/R again.
     */
    private fun rotateGainmapForOutput(
        gainmap: Gainmap,
        rotationDegrees: Int
    ): Gainmap {
        val originalContents = gainmap.gainmapContents
        val transformedContents = orientBitmap(
            source = originalContents,
            rotationDegrees = rotationDegrees
        )
        if (transformedContents === originalContents) return gainmap

        return Gainmap(transformedContents).also { transformed ->
            gainmap.ratioMin.let { transformed.setRatioMin(it[0], it[1], it[2]) }
            gainmap.ratioMax.let { transformed.setRatioMax(it[0], it[1], it[2]) }
            gainmap.gamma.let { transformed.setGamma(it[0], it[1], it[2]) }
            gainmap.epsilonSdr.let { transformed.setEpsilonSdr(it[0], it[1], it[2]) }
            gainmap.epsilonHdr.let { transformed.setEpsilonHdr(it[0], it[1], it[2]) }
            transformed.minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition
            transformed.displayRatioForFullHdr = gainmap.displayRatioForFullHdr
        }
    }

    private fun jpegExifRotationDegrees(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun orientBitmap(
        source: Bitmap,
        rotationDegrees: Int
    ): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return source

        val rotation = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            rotation,
            true
        )
    }

    /** Center-crops an upright capture to the ratio selected in the viewfinder. */
    private fun centerCropToAspect(source: Bitmap, portraitAspectRatio: Float): Bitmap {
        val targetRatio = if (source.width <= source.height) {
            portraitAspectRatio
        } else {
            1f / portraitAspectRatio
        }
        val sourceRatio = source.width.toFloat() / source.height
        if (kotlin.math.abs(sourceRatio - targetRatio) < .001f) return source

        val targetWidth: Int
        val targetHeight: Int
        if (sourceRatio > targetRatio) {
            targetHeight = source.height
            targetWidth = (targetHeight * targetRatio).roundToInt().coerceAtMost(source.width)
        } else {
            targetWidth = source.width
            targetHeight = (targetWidth / targetRatio).roundToInt().coerceAtMost(source.height)
        }
        return Bitmap.createBitmap(
            source,
            (source.width - targetWidth) / 2,
            (source.height - targetHeight) / 2,
            targetWidth,
            targetHeight
        )
    }

    private fun cropGainmapToAspect(gainmap: Gainmap, portraitAspectRatio: Float): Gainmap {
        val originalContents = gainmap.gainmapContents
        val croppedContents = centerCropToAspect(originalContents, portraitAspectRatio)
        if (croppedContents === originalContents) return gainmap

        return Gainmap(croppedContents).also { transformed ->
            gainmap.ratioMin.let { transformed.setRatioMin(it[0], it[1], it[2]) }
            gainmap.ratioMax.let { transformed.setRatioMax(it[0], it[1], it[2]) }
            gainmap.gamma.let { transformed.setGamma(it[0], it[1], it[2]) }
            gainmap.epsilonSdr.let { transformed.setEpsilonSdr(it[0], it[1], it[2]) }
            gainmap.epsilonHdr.let { transformed.setEpsilonHdr(it[0], it[1], it[2]) }
            transformed.minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition
            transformed.displayRatioForFullHdr = gainmap.displayRatioForFullHdr
        }
    }

    private fun finishLook(
        source: Bitmap,
        filtered: Bitmap,
        intensity: Float,
        grain: Float,
        halation: Float
    ): Bitmap {
        val mix = intensity.coerceIn(0f, 1f)
        if (mix >= .999f && grain <= 0f && halation <= 0f) return filtered
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        // Keep the second full-resolution pass in native memory. The previous
        // getPixels -> Kotlin loop -> setPixels path copied tens of megabytes and
        // processed every pixel on one thread after the LUT had already finished.
        if (NativePresetProcessor.finishLook(source, filtered, output, mix, grain, halation)) {
            return output
        }

        // Portable fallback for devices on which the native library cannot load.
        Canvas(output).apply {
            drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            drawBitmap(filtered, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (mix * 255f).roundToInt()
            })
        }
        if (grain <= 0f && halation <= 0f) return output
        val pixels = IntArray(output.width * output.height)
        output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        val grainStrength = grain.coerceIn(0f, 1f) * 22f
        val halationStrength = halation.coerceIn(0f, 1f) * .24f
        for (index in pixels.indices) {
            val color = pixels[index]
            var red = android.graphics.Color.red(color).toFloat()
            var green = android.graphics.Color.green(color).toFloat()
            var blue = android.graphics.Color.blue(color).toFloat()
            if (halationStrength > 0f) {
                val luma = (.2126f * red + .7152f * green + .0722f * blue) / 255f
                val highlight = ((luma - .72f) / .28f).coerceIn(0f, 1f) * halationStrength
                red += 255f * highlight
                green += 72f * highlight
                blue -= 32f * highlight
            }
            if (grainStrength > 0f) {
                var hash = index * 374761393 + 668265263
                hash = (hash xor (hash ushr 13)) * 1274126177
                val noise = ((hash and 0xffff) / 32767.5f - 1f) * grainStrength
                red += noise
                green += noise
                blue += noise
            }
            pixels[index] = android.graphics.Color.argb(
                android.graphics.Color.alpha(color),
                red.roundToInt().coerceIn(0, 255),
                green.roundToInt().coerceIn(0, 255),
                blue.roundToInt().coerceIn(0, 255)
            )
        }
        output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        return output
    }

    private fun beginGallerySave() {
        pendingGallerySaves.incrementAndGet()
        dispatchGallerySaveState()
    }

    private fun finishGallerySave(saved: Boolean) {
        pendingGallerySaves.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        if (saved) savedGalleryRevision.incrementAndGet() else failedGalleryRevision.incrementAndGet()
        dispatchGallerySaveState()
    }

    private fun dispatchGallerySaveState() {
        val state = GallerySaveState(
            pendingCount = pendingGallerySaves.get(),
            savedRevision = savedGalleryRevision.get(),
            failedRevision = failedGalleryRevision.get()
        )
        mainHandler.post { onGallerySaveStateListener?.invoke(state) }
    }

    private fun calculateJpegOrientation(): Int {
        val sensorOrientation = activeCharacteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = activeCharacteristics?.get(CameraCharacteristics.LENS_FACING)
        val direction = if (facing == CameraCharacteristics.LENS_FACING_FRONT) -1 else 1
        return (sensorOrientation + deviceOrientationDegrees * direction + 360) % 360
    }

    private fun applyZoomToRequest(builder: CaptureRequest.Builder, zoomRatio: Float) {
        val activeProfile = cameraProfiles.firstOrNull { it.id == activeCameraId }
        val minimumHardware = activeProfile?.minimumHardwareZoom ?: 1f
        val maximumHardware = activeProfile?.maximumHardwareZoom
            ?: activeCharacteristics
                ?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?.coerceAtLeast(1f)
            ?: 1f
        val supportedZoom = zoomRatio
            .takeIf(Float::isFinite)
            ?.coerceIn(minimumHardware, maximumHardware)
            ?: minimumHardware
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, supportedZoom)
    }

    @Synchronized
    fun closeCamera() {
        closeCameraLocked()
    }

    fun release() {
        stopOrientationTracking()
        closeCamera()
        stopBackgroundThread()
        imageProcessingExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        onTelemetryListener = null
        onRawCapabilityListener = null
        onFocalLengthsListener = null
        onZoomStateListener = null
        onExposureCapabilitiesListener = null
        onPreviewConfigurationListener = null
        onGallerySaveStateListener = null
        onHardwareShutterListener = null
        onDeviceOrientationListener = null
    }

    private fun closeCameraLocked() {
        trackingFocusActive = false
        backgroundHandler?.removeCallbacks(trackingFocusTimeout)
        cameraGeneration++
        cameraOpening = false
        synchronized(captureLock) {
            pendingCapture?.rawImage?.close()
            pendingCapture = null
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewRequestBuilder = null
        activePreviewSurface?.release()
        activePreviewSurface = null
        rawImageReader?.setOnImageAvailableListener(null, null)
        rawImageReader?.close()
        rawImageReader = null
        jpegImageReader?.setOnImageAvailableListener(null, null)
        jpegImageReader?.close()
        jpegImageReader = null
    }
}
