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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.ExifInterface
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.purepixel.camera.gl.createPresetColorMatrix
import com.purepixel.camera.model.LightroomPreset
import com.purepixel.camera.model.NativePresetProcessor
import com.purepixel.camera.model.Preset
import com.purepixel.camera.model.ProcessingMode
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
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

    data class FocalLengthOption(
        val millimeters: Float,
        val zoomRatio: Float
    )

    var onTelemetryListener: ((TelemetryData) -> Unit)? = null
    var onRawCapabilityListener: ((Boolean) -> Unit)? = null
    var onFocalLengthsListener: ((List<FocalLengthOption>) -> Unit)? = null
    var onExposureCapabilitiesListener: ((ExposureCapabilities) -> Unit)? = null
    var onPreviewConfigurationListener: ((Size) -> Unit)? = null
    var onGallerySaveStateListener: ((GallerySaveState) -> Unit)? = null
    var onHardwareShutterListener: (() -> Unit)? = null
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
    private val pendingJpegSaves = AtomicInteger(0)
    private val savedJpegRevision = AtomicInteger(0)
    private val failedJpegRevision = AtomicInteger(0)
    private val imageProcessingExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "HooruImageProcessing")
    }

    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var lastCaptureResult: TotalCaptureResult? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewWidth: Int = 1920
    private var previewHeight: Int = 1440
    private var desiredLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

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
    var currentZoomRatio: Float = 1.0f
    var isFlashEnabled: Boolean = false
        private set
    var flashMode: FlashMode = FlashMode.OFF
        private set
    private var minimumZoomRatio: Float = 1.0f
    private var maximumZoomRatio: Float = 1.0f
    private var meteringGeneration: Int = 0
    @Volatile private var previewStreamPaused: Boolean = false
    @Volatile private var cameraOpening: Boolean = false
    private var cameraGeneration: Int = 0
    // The activity is deliberately portrait-locked, so Display.rotation stays at
    // ROTATION_0 even when the photographer turns the phone. Track the physical
    // orientation separately and snapshot it for every still capture.
    @Volatile private var deviceOrientationDegrees: Int = 0
    private val orientationListener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceOrientationDegrees = ((orientation + 45) / 90 * 90) % 360
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
            val cameraIdList = cameraManager.cameraIdList
            val backCameraId = cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == desiredLensFacing
            } ?: cameraIdList.first()

            activeCharacteristics = cameraManager.getCameraCharacteristics(backCameraId)
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activeCharacteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                    minimumZoomRatio = range.lower
                    maximumZoomRatio = range.upper
                }
            } else {
                minimumZoomRatio = 1.0f
                maximumZoomRatio = activeCharacteristics
                    ?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1.0f
            }

            fun equivalentFocalLengths(characteristics: CameraCharacteristics): List<Float> {
                val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val sensorWidth = physicalSize?.width?.takeIf { it > 0f } ?: return emptyList()
                return (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: floatArrayOf())
                    .filter { it.isFinite() && it > 0f }
                    .map { actualMillimeters -> actualMillimeters * 36f / sensorWidth }
            }
            val active = requireNotNull(activeCharacteristics)
            val primaryEquivalent = equivalentFocalLengths(active).firstOrNull()
            val physicalEquivalents = active.physicalCameraIds.flatMap { physicalId ->
                runCatching {
                    equivalentFocalLengths(cameraManager.getCameraCharacteristics(physicalId))
                }.getOrDefault(emptyList())
            }
            val equivalentFocalLengths = (physicalEquivalents + equivalentFocalLengths(active))
                .filter { it.isFinite() && it > 0f }
                .distinctBy { it.roundToInt() }
                .sorted()
            val focalLengthOptions = equivalentFocalLengths.mapNotNull { equivalent ->
                val zoomRatio = if (primaryEquivalent != null && primaryEquivalent > 0f) {
                    equivalent / primaryEquivalent
                } else 1f
                zoomRatio.takeIf { it in minimumZoomRatio..maximumZoomRatio }?.let {
                    FocalLengthOption(
                        millimeters = equivalent,
                        zoomRatio = it
                    )
                }
            }.ifEmpty {
                primaryEquivalent?.let { listOf(FocalLengthOption(it, 1f)) }.orEmpty()
            }
            updateAvailableFocalLengths(focalLengthOptions)

            // Setup RAW and JPEG ImageReaders
            val supportsRaw = requestCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            val rawCaptureSupported = supportsRaw && rawSize != null
            updateRawCapability(rawCaptureSupported)
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1440)

            rawImageReader = rawSize?.takeIf { rawCaptureSupported }?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            }
            jpegImageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)

            val previewSize = choosePreviewSize(map, width, height)
            currentPreviewSize = previewSize
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            onPreviewConfigurationListener?.invoke(previewSize)
            val previewSurface = Surface(surfaceTexture)
            val captureSurface = jpegImageReader?.surface

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
                        createCaptureSession(previewSurface, captureSurface, camera, openGeneration)
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

    fun setCaptureFilter(preset: Preset) {
        activeFilterId = preset.id
        activeLightroomPreset = preset.lightroom
        activeLookIntensity = preset.intensity
        activeLookGrain = preset.grain
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
        activeProcessingMode = if (presetId == "android_processing") {
            ProcessingMode.ANDROID
        } else if (presetId == "no_filter") {
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
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val sensor = activeCharacteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val generation = ++meteringGeneration
        val crop = cropRectForZoom(sensor, currentZoomRatio)
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
        val spot = MeteringRectangle(spotRect, MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            if ((activeCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(spot))
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            if ((activeCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(spot))
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Spot metering/focus failed", e)
        }

        backgroundHandler?.postDelayed({
            if (generation == meteringGeneration) restoreAverageMetering()
        }, 6000L)
    }

    private fun restoreAverageMetering() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyAverageMetering(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
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
        // Android 9 stores captures in this app's external-media directory. Reading
        // that directory does not require the legacy broad storage permissions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val latestFile = getOutputDirectory()
                .listFiles { file ->
                    file.isFile && file.name.startsWith("hooru_") &&
                        file.extension.equals("jpg", ignoreCase = true)
                }
                ?.maxByOrNull(File::lastModified)
                ?: return null
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                latestFile
            )
        }

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
        captureSurface: Surface?,
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
            val surfaces = listOfNotNull(previewSurface, captureSurface)

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)

                applyImageProcessingPreference(this)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                activeCharacteristics
                    ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.upper <= 30 }
                    ?.maxWithOrNull(compareBy<android.util.Range<Int>> { it.upper }.thenBy { it.lower })
                    ?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                applyAverageMetering(this)
            }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
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
                    Log.e(TAG, "Camera capture session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            previewSurface.release()
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    @Volatile private var pendingZoomRatio: Float? = null
    @Volatile private var zoomUpdateQueued = false

    /**
     * Coalesce fast touch updates onto Camera2's handler. This keeps binder work out
     * of Compose's input path while always applying the most recent finger position.
     */
    fun setZoomRatio(zoomRatio: Float) {
        val supportedZoom = zoomRatio.coerceIn(minimumZoomRatio, maximumZoomRatio)
        currentZoomRatio = supportedZoom
        pendingZoomRatio = supportedZoom
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
            if (pendingZoomRatio != null) setZoomRatio(pendingZoomRatio!!)
        } ?: run { zoomUpdateQueued = false }
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
        if (activeProcessingMode != ProcessingMode.ANDROID) {
            disableOptionalImageProcessing(builder)
            return
        }
        val chars = activeCharacteristics ?: return
        chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?.firstOrNull { it == CaptureRequest.EDGE_MODE_HIGH_QUALITY || it == CaptureRequest.EDGE_MODE_FAST }
            ?.let { builder.set(CaptureRequest.EDGE_MODE, it) }
        chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.firstOrNull {
                it == CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY ||
                    it == CaptureRequest.NOISE_REDUCTION_MODE_FAST
            }
            ?.let { builder.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
            ?.firstOrNull { it == CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY }
            ?.let { builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
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
                    lastCaptureResult = result
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    val expTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 20000000L

                    currentIso = iso
                    currentExposureTimeNs = expTimeNs
                    if (manualIso == null && manualExposureTimeNs == null) {
                        baseAutoIso = iso
                        baseAutoExposureTimeNs = expTimeNs
                    }
                    currentShutterStr = formatExposureTime(expTimeNs)

                    mainHandler.post {
                        onTelemetryListener?.invoke(TelemetryData(
                            iso = currentIso,
                            shutterSpeed = currentShutterStr,
                            exposureTimeNs = currentExposureTimeNs,
                            format = captureFormat,
                            exposureCompensation = exposureCompensationEv,
                            isoManual = manualIso != null,
                            shutterManual = manualExposureTimeNs != null
                        ))
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

    fun triggerCapture(): Long {
        val blackoutDuration = (currentExposureTimeNs / 1_000_000L + 45L).coerceIn(65L, 1500L)
        val device = cameraDevice ?: return blackoutDuration
        val session = captureSession ?: return blackoutDuration

        // Keep the capture path safe if a camera capability changes while the
        // preview is being recreated or the lens is being switched.
        if (!supportsRawCapture && captureFormat != "JPG") {
            captureFormat = "JPG"
        }

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val targets = mutableListOf<Surface>()

            if ((captureFormat == "RAW" || captureFormat == "RAW+JPG") && rawImageReader != null) {
                rawImageReader?.surface?.let {
                    captureBuilder.addTarget(it)
                    targets.add(it)
                }
            }

            if (captureFormat == "JPG" || captureFormat == "RAW+JPG") {
                jpegImageReader?.surface?.let {
                    captureBuilder.addTarget(it)
                    targets.add(it)
                }
            }

            applyImageProcessingPreference(captureBuilder)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyExposureSettings(captureBuilder)
            applyZoomToRequest(captureBuilder, currentZoomRatio)
            val jpegOrientation = calculateJpegOrientation()
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            val filterAtCapture = activeFilterId
            val lightroomAtCapture = activeLightroomPreset
            val intensityAtCapture = activeLookIntensity
            val grainAtCapture = activeLookGrain
            val halationAtCapture = activeLookHalation
            val aspectRatioAtCapture = captureAspectRatio
            when (flashMode) {
                FlashMode.ON -> captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                FlashMode.AUTO -> captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                FlashMode.OFF -> Unit
            }

            // RAW Capture Listener
            rawImageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val chars = activeCharacteristics
                    val result = lastCaptureResult
                    if (chars != null && result != null) {
                        imageProcessingExecutor.execute {
                            try {
                                val dngCreator = DngCreator(chars, result)
                                val file = File(getOutputDirectory(), "hooru_${System.currentTimeMillis()}.dng")
                                FileOutputStream(file).use { out -> dngCreator.writeImage(out, image) }
                                dngCreator.close()
                                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                                Log.i(TAG, "RAW DNG Saved: ${file.absolutePath}")
                            } catch (t: Throwable) {
                                Log.e(TAG, "Unable to finish RAW capture", t)
                            } finally {
                                image.close()
                            }
                        }
                    } else {
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "RAW capture was interrupted by lifecycle change", e)
                }
            }, backgroundHandler)

            // JPEG Capture Listener
            jpegImageReader?.setOnImageAvailableListener({ reader ->
                var image: android.media.Image? = null
                var jpegBytes: ByteArray? = null
                try {
                    image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    jpegBytes = ByteArray(buffer.remaining())
                    buffer.get(requireNotNull(jpegBytes))
                } catch (e: Exception) {
                    Log.w(TAG, "JPEG capture was interrupted by lifecycle change", e)
                    return@setOnImageAvailableListener
                } finally {
                    // Camera2 may stall every output while a maximum-resolution
                    // JPEG remains acquired. Release it before filtering starts.
                    image?.close()
                }
                val capturedBytes = jpegBytes ?: return@setOnImageAvailableListener
                beginJpegSave()
                imageProcessingExecutor.execute {
                    val saved = saveCapturedJpeg(
                        capturedBytes,
                        jpegOrientation,
                        filterAtCapture,
                        lightroomAtCapture,
                        intensityAtCapture,
                        grainAtCapture,
                        halationAtCapture,
                        aspectRatioAtCapture
                    )
                    finishJpegSave(saved)
                }
            }, backgroundHandler)

            if (targets.isNotEmpty()) {
                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    private fun restorePreview() {
                        if (captureSession === session && !previewStreamPaused) startPreviewRepeatingRequest()
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) = restorePreview()

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) = restorePreview()
                }, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture image", e)
        }
        return blackoutDuration
    }

    fun requestHardwareShutter() {
        mainHandler.post {
            onHardwareShutterListener?.invoke() ?: triggerCapture()
        }
    }

    private fun saveCapturedJpeg(
        bytes: ByteArray,
        jpegOrientation: Int,
        filterId: String,
        lightroomPreset: LightroomPreset?,
        intensity: Float,
        grain: Float,
        halation: Float,
        aspectRatio: Float
    ): Boolean {
        val displayName = "hooru_${System.currentTimeMillis()}_${System.nanoTime() % 1000}.jpg"
        var pendingUri: Uri? = null
        var pendingFile: File? = null
        var publishedFile: File? = null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    writeCapturedJpeg(out, bytes, jpegOrientation, filterId, lightroomPreset, intensity, grain, halation, aspectRatio)
                } ?: error("MediaStore output stream could not be opened")
                val published = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                check(context.contentResolver.update(requireNotNull(pendingUri), published, null, null) > 0) {
                    "MediaStore entry could not be published"
                }
                Log.i(TAG, "JPEG published: $pendingUri")
            } else {
                val directory = getOutputDirectory()
                pendingFile = File(directory, ".$displayName.pending")
                publishedFile = File(directory, displayName)
                FileOutputStream(requireNotNull(pendingFile)).use { out ->
                    writeCapturedJpeg(out, bytes, jpegOrientation, filterId, lightroomPreset, intensity, grain, halation, aspectRatio)
                }
                check(requireNotNull(pendingFile).renameTo(requireNotNull(publishedFile))) {
                    "Completed JPEG could not be published"
                }
                pendingFile = null
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(requireNotNull(publishedFile).absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                Log.i(TAG, "JPEG published: ${publishedFile?.absolutePath}")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to finish JPEG capture", t)
            pendingUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            pendingFile?.delete()
            publishedFile?.delete()
            false
        }
    }

    private fun writeCapturedJpeg(
        output: OutputStream,
        bytes: ByteArray,
        jpegOrientation: Int,
        filterId: String,
        lightroomPreset: LightroomPreset?,
        intensity: Float,
        grain: Float,
        halation: Float,
        aspectRatio: Float
    ) {
        var working: Bitmap? = null
        var filtered: Bitmap? = null
        var finished: Bitmap? = null
        try {
            val colorMatrix = createPresetColorMatrix(filterId)
            val needsCrop = kotlin.math.abs(aspectRatio - 3f / 4f) > .001f
            if (colorMatrix == null && lightroomPreset == null && grain <= 0f && halation <= 0f && !needsCrop) {
                output.write(bytes)
            } else {
                working = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Camera JPEG could not be decoded")
                val source = requireNotNull(working)
                val orientedAndCropped = orientAndCrop(
                    source = source,
                    // Most HALs expose the requested JPEG orientation as EXIF.
                    // A few omit that tag on the byte stream sent to ImageReader;
                    // then query the sensor again here, immediately before the
                    // gallery file is rendered. This also covers a rotation in the
                    // short interval between shutter press and image processing.
                    rotationDegrees = jpegExifRotationDegrees(bytes)
                        .takeIf { it != 0 }
                        ?: calculateJpegOrientation(),
                    targetWidthOverHeight = aspectRatio
                )
                if (orientedAndCropped !== source) {
                    source.recycle()
                    working = orientedAndCropped
                }
                filtered = if (lightroomPreset != null) {
                    lightroomPreset.applyToBitmap(requireNotNull(working))
                } else if (colorMatrix != null) Bitmap.createBitmap(
                    requireNotNull(working).width,
                    requireNotNull(working).height,
                    Bitmap.Config.ARGB_8888
                ).also { result ->
                    Canvas(result).drawBitmap(requireNotNull(working), 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(requireNotNull(colorMatrix))
                    })
                } else requireNotNull(working)
                finished = finishLook(
                    source = requireNotNull(working),
                    filtered = requireNotNull(filtered),
                    intensity = intensity,
                    grain = grain,
                    halation = halation
                )
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

    private fun orientAndCrop(
        source: Bitmap,
        rotationDegrees: Int,
        targetWidthOverHeight: Float
    ): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val targetAfterRotation = targetWidthOverHeight.coerceIn(9f / 21f, 1f)
        val axesSwap = normalizedRotation == 90 || normalizedRotation == 270
        val targetBeforeRotation = if (axesSwap) 1f / targetAfterRotation else targetAfterRotation
        val current = source.width.toFloat() / source.height

        var cropX = 0
        var cropY = 0
        var cropWidth = source.width
        var cropHeight = source.height
        if (kotlin.math.abs(current - targetBeforeRotation) >= .002f) {
            if (current > targetBeforeRotation) {
                cropWidth = (source.height * targetBeforeRotation).roundToInt().coerceAtMost(source.width)
                cropX = (source.width - cropWidth) / 2
            } else {
                cropHeight = (source.width / targetBeforeRotation).roundToInt().coerceAtMost(source.height)
                cropY = (source.height - cropHeight) / 2
            }
        }

        if (normalizedRotation == 0 && cropX == 0 && cropY == 0 &&
            cropWidth == source.width && cropHeight == source.height
        ) return source

        val rotation = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        return Bitmap.createBitmap(
            source,
            cropX,
            cropY,
            cropWidth,
            cropHeight,
            rotation,
            true
        )
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

    private fun beginJpegSave() {
        pendingJpegSaves.incrementAndGet()
        dispatchGallerySaveState()
    }

    private fun finishJpegSave(saved: Boolean) {
        pendingJpegSaves.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        if (saved) savedJpegRevision.incrementAndGet() else failedJpegRevision.incrementAndGet()
        dispatchGallerySaveState()
    }

    private fun dispatchGallerySaveState() {
        val state = GallerySaveState(
            pendingCount = pendingJpegSaves.get(),
            savedRevision = savedJpegRevision.get(),
            failedRevision = failedJpegRevision.get()
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
        val supportedZoom = zoomRatio.coerceIn(minimumZoomRatio, maximumZoomRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, supportedZoom)
        } else {
            val sensorRect = activeCharacteristics
                ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropWidth = (sensorRect.width() / supportedZoom).toInt()
            val cropHeight = (sensorRect.height() / supportedZoom).toInt()
            val left = (sensorRect.width() - cropWidth) / 2
            val top = (sensorRect.height() - cropHeight) / 2
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(left, top, left + cropWidth, top + cropHeight)
            )
        }
    }

    private fun getOutputDirectory(): File {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera"
            ).apply { mkdirs() }
        }
        val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
            File(it, "hooru").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir
    }

    @Synchronized
    fun closeCamera() {
        closeCameraLocked()
    }

    private fun closeCameraLocked() {
        cameraGeneration++
        cameraOpening = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewRequestBuilder = null
        activePreviewSurface?.release()
        activePreviewSurface = null
        rawImageReader?.close()
        rawImageReader = null
        jpegImageReader?.close()
        jpegImageReader = null
    }
}
