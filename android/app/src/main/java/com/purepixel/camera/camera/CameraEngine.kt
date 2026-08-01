package com.purepixel.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.purepixel.camera.gl.createPresetColorMatrix
import com.purepixel.camera.model.LightroomPreset
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "HooruCameraEngine"
    }

    data class TelemetryData(
        val iso: Int = 100,
        val shutterSpeed: String = "1/50",
        val format: String = "JPG"
    )

    data class GallerySaveState(
        val pendingCount: Int = 0,
        val savedRevision: Int = 0,
        val failedRevision: Int = 0
    )

    var onTelemetryListener: ((TelemetryData) -> Unit)? = null
    var onPreviewConfigurationListener: ((Size) -> Unit)? = null
    var onGallerySaveStateListener: ((GallerySaveState) -> Unit)? = null
    var currentPreviewSize: Size? = null
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
    var isIsoBoostEnabled: Boolean = false
        private set
    var isShutterBoostEnabled: Boolean = false
        private set
    private var activeFilterId: String = "no_filter"
    private var activeLightroomPreset: LightroomPreset? = null
    var captureFormat: String = "JPG" // "JPG" | "RAW" | "RAW+JPG"
    var currentZoomRatio: Float = 1.0f
    var isFlashEnabled: Boolean = false
        private set
    private var minimumZoomRatio: Float = 1.0f
    private var maximumZoomRatio: Float = 1.0f
    private var meteringGeneration: Int = 0
    @Volatile private var previewStreamPaused: Boolean = false
    @Volatile private var cameraOpening: Boolean = false
    private var cameraGeneration: Int = 0

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

    @SuppressLint("MissingPermission")
    @Synchronized
    fun openCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (surfaceTexture.isReleased) return
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

            // Setup RAW and JPEG ImageReaders
            val capabilities = activeCharacteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            val supportsRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1440)

            rawImageReader = if (supportsRaw && rawSize != null) {
                ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            } else null
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
        closeCamera()
        isFlashEnabled = false
        val texture = previewSurfaceTexture ?: return
        backgroundHandler?.post {
            openCamera(texture, previewWidth, previewHeight)
        }
    }

    fun toggleFlash(): Boolean {
        val hasFlash = activeCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val builder = previewRequestBuilder ?: return false
        val session = captureSession ?: return false
        if (!hasFlash) {
            isFlashEnabled = false
            return false
        }
        isFlashEnabled = !isFlashEnabled
        return try {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (isFlashEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            isFlashEnabled
        } catch (e: Exception) {
            isFlashEnabled = false
            Log.e(TAG, "Unable to toggle flash", e)
            false
        }
    }

    fun setCaptureFilter(presetId: String, lightroomPreset: LightroomPreset? = null) {
        activeFilterId = presetId
        activeLightroomPreset = lightroomPreset
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
        isIsoBoostEnabled = false
        isShutterBoostEnabled = false
        applyExposureBoost()
    }

    fun meterAndFocus(normalizedX: Float, normalizedY: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val sensor = activeCharacteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        disableExposureBoosts()
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

    fun toggleIsoBoost(): Boolean {
        isIsoBoostEnabled = !isIsoBoostEnabled
        applyExposureBoost()
        return isIsoBoostEnabled
    }

    fun toggleShutterBoost(): Boolean {
        isShutterBoostEnabled = !isShutterBoostEnabled
        applyExposureBoost()
        return isShutterBoostEnabled
    }

    private fun applyExposureBoost() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            applyExposureSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to apply exposure boost", e)
        }
    }

    private fun applyExposureSettings(builder: CaptureRequest.Builder) {
        if (!isIsoBoostEnabled && !isShutterBoostEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            return
        }
        val capabilities = activeCharacteristics
            ?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            val compensationRange = activeCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                ((if (isIsoBoostEnabled) 1 else 0) + (if (isShutterBoostEnabled) 1 else 0))
                    .coerceIn(compensationRange?.lower ?: 0, compensationRange?.upper ?: 0)
            )
            return
        }
        val isoRange = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = (baseAutoIso * if (isIsoBoostEnabled) 2 else 1)
            .coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 6400)
        val exposure = (baseAutoExposureTimeNs * if (isShutterBoostEnabled) 2L else 1L)
            .coerceIn(exposureRange?.lower ?: 100_000L, exposureRange?.upper ?: 1_000_000_000L)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
    }

    fun openNativeGallery(): Boolean {
        try {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val selection = buildString {
                append("${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?")
                append(" AND ${MediaStore.Images.Media.SIZE} > 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append(" AND ${MediaStore.Images.Media.IS_PENDING} = 0")
                }
            }
            val latest = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                arrayOf("hooru_%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                }
            } ?: return false
            val reviewIntent = Intent("com.android.camera.action.REVIEW", latest).apply {
                setDataAndType(latest, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val intent = if (reviewIntent.resolveActivity(context.packageManager) != null) reviewIntent else {
                Intent(Intent.ACTION_VIEW, latest).apply {
                    setDataAndType(latest, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open native gallery", e)
            return false
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

                disableOptionalImageProcessing(this)
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

    fun setZoomRatio(zoomRatio: Float) {
        val supportedZoom = zoomRatio.coerceIn(minimumZoomRatio, maximumZoomRatio)
        currentZoomRatio = supportedZoom
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, supportedZoom)
            } else {
                val chars = activeCharacteristics ?: return
                val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                val cropWidth = (sensorRect.width() / supportedZoom).toInt()
                val cropHeight = (sensorRect.height() / supportedZoom).toInt()
                val left = (sensorRect.width() - cropWidth) / 2
                val top = (sensorRect.height() - cropHeight) / 2
                val cropRect = Rect(left, top, left + cropWidth, top + cropHeight)
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) == true
        ) builder.set(
            CaptureRequest.DISTORTION_CORRECTION_MODE,
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
        )
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
                    if (!isIsoBoostEnabled && !isShutterBoostEnabled) {
                        baseAutoIso = iso
                        baseAutoExposureTimeNs = expTimeNs
                    }
                    currentShutterStr = formatExposureTime(expTimeNs)

                    onTelemetryListener?.invoke(
                        TelemetryData(
                            iso = currentIso,
                            shutterSpeed = currentShutterStr,
                            format = captureFormat
                        )
                    )
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview request", e)
        }
    }

    private fun formatExposureTime(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format("%.1fs", seconds)
        } else {
            val denominator = Math.round(1.0 / seconds)
            "1/$denominator"
        }
    }

    fun triggerCapture(): Long {
        val blackoutDuration = (currentExposureTimeNs / 1_000_000L + 45L).coerceIn(65L, 1500L)
        val device = cameraDevice ?: return blackoutDuration
        val session = captureSession ?: return blackoutDuration

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

            disableOptionalImageProcessing(captureBuilder)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyExposureSettings(captureBuilder)
            applyZoomToRequest(captureBuilder, currentZoomRatio)
            val jpegOrientation = calculateJpegOrientation()
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            val filterAtCapture = activeFilterId
            val lightroomAtCapture = activeLightroomPreset
            if (isFlashEnabled) {
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
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
                        lightroomAtCapture
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

    private fun saveCapturedJpeg(
        bytes: ByteArray,
        jpegOrientation: Int,
        filterId: String,
        lightroomPreset: LightroomPreset?
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hooru")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                pendingUri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: error("MediaStore entry could not be created")
                context.contentResolver.openOutputStream(requireNotNull(pendingUri), "w")?.use { out ->
                    writeCapturedJpeg(out, bytes, jpegOrientation, filterId, lightroomPreset)
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
                    writeCapturedJpeg(out, bytes, jpegOrientation, filterId, lightroomPreset)
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
        lightroomPreset: LightroomPreset?
    ) {
        var working: Bitmap? = null
        var filtered: Bitmap? = null
        try {
            val colorMatrix = createPresetColorMatrix(filterId)
            if (colorMatrix == null && lightroomPreset == null) {
                output.write(bytes)
            } else {
                working = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Camera JPEG could not be decoded")
                val source = requireNotNull(working)
                val rotation = Matrix().apply { postRotate(jpegOrientation.toFloat()) }
                val oriented = Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height, rotation, true
                )
                if (oriented !== source) {
                    source.recycle()
                    working = oriented
                }
                filtered = if (lightroomPreset != null) {
                    lightroomPreset.applyToBitmap(requireNotNull(working))
                } else Bitmap.createBitmap(
                    requireNotNull(working).width,
                    requireNotNull(working).height,
                    Bitmap.Config.ARGB_8888
                ).also { result ->
                    Canvas(result).drawBitmap(requireNotNull(working), 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(requireNotNull(colorMatrix))
                    })
                }
                check(requireNotNull(filtered).compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "JPEG encoder failed"
                }
            }
        } finally {
            filtered?.takeIf { it !== working && !it.isRecycled }?.recycle()
            working?.takeIf { !it.isRecycled }?.recycle()
        }
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
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = activeCharacteristics?.get(CameraCharacteristics.LENS_FACING)
        val direction = if (facing == CameraCharacteristics.LENS_FACING_FRONT) -1 else 1
        return (sensorOrientation + displayDegrees * direction + 360) % 360
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
