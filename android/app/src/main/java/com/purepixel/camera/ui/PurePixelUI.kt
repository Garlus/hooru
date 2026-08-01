package com.purepixel.camera.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.purepixel.camera.camera.CameraEngine
import com.purepixel.camera.gl.CameraPreviewGL
import com.purepixel.camera.gl.GLCameraView
import com.purepixel.camera.gl.PreviewAnalysis
import com.purepixel.camera.model.Preset
import com.purepixel.camera.model.LightroomPreset
import com.purepixel.camera.model.FilterPreviewCache
import com.purepixel.camera.model.FilterPreview
import com.purepixel.camera.model.PresetLibrary
import com.purepixel.camera.model.ProcessingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

enum class UiStateMode {
    IDLE,          // Image 1: Telemetry on shutter row, minimal 1-tick zoom below
    ZOOM_ACTIVE,   // Image 3: Expanded Gaussian Zoom Rocker dial below shutter
    FILTER_ACTIVE  // Image 2: Swipe on shutter opens Filter Carousel, auto-fades back after 2s
}

private fun buildShutterPresets(
    builtIns: List<Preset>,
    custom: List<Preset>,
    selectedIds: Set<String>
): List<Preset> {
    val selectableBuiltIns = builtIns.filterNot(Preset::isAddButton)
    val selected = (selectableBuiltIns + custom).filter { it.id == "no_filter" || it.id in selectedIds }
    return selected + builtIns.first(Preset::isAddButton)
}

private fun formatExposureTime(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) {
        if (seconds >= 10) "${seconds.roundToInt()}s" else String.format(Locale.US, "%.1fs", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()}"
    }
}

private fun isoStops(capabilities: CameraEngine.ExposureCapabilities): List<Int> = listOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
    1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
).filter { it in capabilities.minimumIso..capabilities.maximumIso }
    .ifEmpty { listOf(capabilities.minimumIso, capabilities.maximumIso).distinct() }

private fun shutterStops(capabilities: CameraEngine.ExposureCapabilities): List<Long> = listOf(
    125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L,
    8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L,
    250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L,
    4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L
).filter { it in capabilities.minimumExposureTimeNs..capabilities.maximumExposureTimeNs }
    .ifEmpty { listOf(capabilities.minimumExposureTimeNs, capabilities.maximumExposureTimeNs).distinct() }

private fun formatFocalLength(millimeters: Float): String {
    val rounded = millimeters.roundToInt()
    return if (abs(millimeters - rounded) < 0.05f) {
        "${rounded}mm"
    } else {
        String.format(Locale.US, "%.1fmm", millimeters)
    }
}

private fun hdrBoost(color: Color, multiplier: Float = 1.28f): Color = Color(
    red = color.red * multiplier,
    green = color.green * multiplier,
    blue = color.blue * multiplier,
    alpha = color.alpha,
    colorSpace = ColorSpaces.ExtendedSrgb
)

private val HdrWhite = Color(1.32f, 1.32f, 1.32f, 1f, ColorSpaces.ExtendedSrgb)

private fun applyPresetToPreview(view: GLCameraView?, preset: Preset) {
    view?.applyPreset(preset)
}

private fun Modifier.springClickable(
    feedback: Int = HapticFeedbackConstants.CLOCK_TICK,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .96f else 1f,
        animationSpec = spring(dampingRatio = .58f, stiffness = 620f),
        label = "springClick"
    )
    val view = LocalView.current
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = LocalIndication.current) {
            view.performHapticFeedback(feedback)
            onClick()
        }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PurePixelScreen(
    cameraEngine: CameraEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val scope = rememberCoroutineScope()
    val presetLibrary = remember { PresetLibrary(context.applicationContext) }
    val previewCache = remember { FilterPreviewCache(context.applicationContext) }
    val cameraSettings = remember {
        context.applicationContext.getSharedPreferences("camera_settings", android.content.Context.MODE_PRIVATE)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var telemetry by remember { mutableStateOf(CameraEngine.TelemetryData()) }
    var exposureCapabilities by remember { mutableStateOf(cameraEngine.exposureCapabilities) }
    var rawCaptureSupported by remember { mutableStateOf(cameraEngine.supportsRawCapture) }
    var glView by remember { mutableStateOf<GLCameraView?>(null) }
    var backgroundFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }
    var manualIso by remember { mutableStateOf<Int?>(null) }
    var manualShutterNs by remember { mutableStateOf<Long?>(null) }
    var exposureCompensation by remember { mutableFloatStateOf(0f) }
    var previewBlackout by remember { mutableStateOf(false) }
    var blackoutDurationMs by remember { mutableLongStateOf(80L) }
    var shutterSequence by remember { mutableIntStateOf(0) }
    var shutterPressed by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusSequence by remember { mutableIntStateOf(0) }

    var builtInPresets by remember { mutableStateOf(presetLibrary.builtInPresets()) }
    var customPresets by remember { mutableStateOf(presetLibrary.customPresets()) }
    var rememberLastFilter by remember {
        mutableStateOf(cameraSettings.getBoolean("remember_last_filter", false))
    }
    val defaultSelectedIds = remember {
        builtInPresets.filterNot(Preset::isAddButton).map(Preset::id).toSet()
    }
    var selectedPresetIds by remember {
        mutableStateOf(presetLibrary.selectedIds(defaultSelectedIds))
    }
    var presets by remember {
        mutableStateOf(buildShutterPresets(builtInPresets, customPresets, selectedPresetIds))
    }
    var activePresetIndex by remember {
        val initialPresetId = if (cameraSettings.getBoolean("remember_last_filter", false)) {
            cameraSettings.getString("last_filter_id", "no_filter") ?: "no_filter"
        } else {
            "no_filter"
        }
        mutableIntStateOf(
            presets.indexOfFirst { it.id == initialPresetId }
                .takeIf { it >= 0 }
                ?: presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
        )
    }
    var pendingPresetIndex by remember { mutableIntStateOf(activePresetIndex) }
    var uiMode by remember { mutableStateOf(UiStateMode.IDLE) }
    var showPresetSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<Preset?>(null) }
    var isImportingPreset by remember { mutableStateOf(false) }
    var recentRevision by remember { mutableIntStateOf(0) }
    var gallerySaveState by remember { mutableStateOf(CameraEngine.GallerySaveState()) }
    val galleryArrivalScale = remember { Animatable(1f) }
    var showGalleryArrival by remember { mutableStateOf(false) }
    var galleryThumbnail by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var previewAnalysis by remember { mutableStateOf(PreviewAnalysis()) }
    var analysisEnabled by remember { mutableStateOf(cameraSettings.getBoolean("analysis_enabled", false)) }
    var zebraMode by remember { mutableIntStateOf(cameraSettings.getInt("zebra_mode", 0)) }
    var focusPeakingEnabled by remember { mutableStateOf(cameraSettings.getBoolean("focus_peaking", false)) }
    var selectedAspectRatio by remember { mutableFloatStateOf(cameraSettings.getFloat("aspect_ratio", 3f / 4f)) }
    var gridEnabled by remember { mutableStateOf(cameraSettings.getBoolean("grid", false)) }
    var levelEnabled by remember { mutableStateOf(cameraSettings.getBoolean("level", false)) }
    var timerSeconds by remember { mutableIntStateOf(cameraSettings.getInt("timer_seconds", 0)) }
    var countdown by remember { mutableIntStateOf(0) }
    var deviceRoll by remember { mutableFloatStateOf(0f) }
    
    // The camera engine supplies the active phone camera's focal lengths as labels
    // for the full zoom scale; it does not define the scale itself.
    var availableFocalLengths by remember { mutableStateOf(cameraEngine.availableFocalLengths) }
    var currentZoom by remember { mutableFloatStateOf(1.3f) }
    val smoothedZoom by animateFloatAsState(
        targetValue = currentZoom,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "smoothedCameraZoom"
    )
    var continuousZoom by remember { mutableFloatStateOf(currentZoom) }
    var rockerMotion by remember { mutableFloatStateOf(0f) }
    var filterInteractionCounter by remember { mutableIntStateOf(0) }
    var zoomInteractionCounter by remember { mutableIntStateOf(0) }

    // Native multi-XMP import. Parsing, LUT generation and card rendering stay off the UI thread.
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isImportingPreset = true
            val progressSnackbar = launch {
                snackbarHostState.showSnackbar(
                    message = if (uris.size == 1) {
                        "Lightroom-Preset wird importiert und konvertiert …"
                    } else {
                        "${uris.size} Lightroom-Presets werden importiert und konvertiert …"
                    },
                    duration = SnackbarDuration.Indefinite
                )
            }
            val importedPresets = mutableListOf<Preset>()
            val failures = mutableListOf<Throwable>()
            try {
                uris.forEach { uri ->
                    runCatching {
                        val imported = withContext(Dispatchers.IO) {
                            val displayName = context.contentResolver.query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            } ?: "Lightroom"
                            val text = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: error("${displayName}: Datei konnte nicht gelesen werden.")
                            presetLibrary.importXmp(text, displayName.substringBeforeLast('.'))
                        }
                        // Finish every conversion before publishing the updated library.
                        previewCache.preview(imported)
                        imported
                    }.onSuccess(importedPresets::add)
                        .onFailure(failures::add)
                }
                if (importedPresets.isNotEmpty()) {
                    val updatedCustom = presetLibrary.customPresets()
                    val nextIds = selectedPresetIds + importedPresets.map(Preset::id)
                    customPresets = updatedCustom
                    selectedPresetIds = nextIds
                    presetLibrary.saveSelected(nextIds)
                    presets = buildShutterPresets(builtInPresets, updatedCustom, nextIds)
                    recentRevision++
                }
            } finally {
                snackbarHostState.currentSnackbarData?.dismiss()
                progressSnackbar.cancel()
                isImportingPreset = false
            }
            val importedCount = importedPresets.map(Preset::id).distinct().size
            val resultMessage = when {
                failures.isEmpty() && importedCount == 1 ->
                    "Preset importiert, konvertiert und zur Kamera-Leiste hinzugefügt."
                failures.isEmpty() ->
                    "$importedCount Presets importiert, konvertiert und zur Kamera-Leiste hinzugefügt."
                importedCount == 0 ->
                    failures.firstOrNull()?.message ?: "Die Presets konnten nicht importiert werden."
                else ->
                    "$importedCount Presets importiert, ${failures.size} konnten nicht verarbeitet werden."
            }
            snackbarHostState.showSnackbar(resultMessage)
        }
    }

    val capturePhoto: () -> Unit = {
        if (countdown == 0) {
            scope.launch {
                shutterPressed = false
                if (timerSeconds > 0) {
                    for (remaining in timerSeconds downTo 1) {
                        countdown = remaining
                        hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        delay(1000L)
                    }
                    countdown = 0
                }
                blackoutDurationMs = cameraEngine.triggerCapture()
                shutterSequence++
                hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }
    val latestCapturePhoto by rememberUpdatedState(capturePhoto)

    // 2.5-Second Inactivity Timer for Zoom Rocker -> Fades back to IDLE state
    LaunchedEffect(uiMode, currentZoom, zoomInteractionCounter) {
        if (uiMode == UiStateMode.ZOOM_ACTIVE) {
            delay(1100)
            uiMode = UiStateMode.IDLE
        }
    }

    LaunchedEffect(shutterSequence) {
        if (shutterSequence > 0) {
            previewBlackout = true
            delay(blackoutDurationMs)
            previewBlackout = false
        }
    }

    LaunchedEffect(focusSequence) {
        if (focusSequence > 0) {
            delay(1800)
            focusPoint = null
        }
    }

    LaunchedEffect(gallerySaveState.savedRevision) {
        if (gallerySaveState.savedRevision > 0) {
            galleryThumbnail = withContext(Dispatchers.IO) {
                cameraEngine.loadLatestGalleryThumbnail()
            }?.asImageBitmap()
            showGalleryArrival = true
            galleryArrivalScale.snapTo(.86f)
            galleryArrivalScale.animateTo(1.16f, tween(durationMillis = 170))
            galleryArrivalScale.animateTo(
                1f,
                spring(dampingRatio = .48f, stiffness = 520f)
            )
            delay(1800L)
            showGalleryArrival = false
        }
    }


    LaunchedEffect(Unit) {
        galleryThumbnail = withContext(Dispatchers.IO) {
            cameraEngine.loadLatestGalleryThumbnail()
        }?.asImageBitmap()
    }

    LaunchedEffect(gallerySaveState.failedRevision) {
        if (gallerySaveState.failedRevision > 0) {
            snackbarHostState.showSnackbar("Das Foto konnte nicht in der Galerie gespeichert werden.")
        }
    }

    LaunchedEffect(smoothedZoom) {
        cameraEngine.setZoomRatio(smoothedZoom)
    }

    // Listen to real-time camera telemetry and the active camera's physical lenses.
    DisposableEffect(Unit) {
        rawCaptureSupported = cameraEngine.supportsRawCapture
        cameraEngine.onRawCapabilityListener = { supported ->
            rawCaptureSupported = supported
        }
        cameraEngine.onTelemetryListener = { data ->
            telemetry = data
        }
        availableFocalLengths = cameraEngine.availableFocalLengths
        cameraEngine.onFocalLengthsListener = { options ->
            availableFocalLengths = options
        }
        exposureCapabilities = cameraEngine.exposureCapabilities
        cameraEngine.onExposureCapabilitiesListener = { capabilities ->
            exposureCapabilities = capabilities
            if (!capabilities.manualSensor) {
                manualIso = null
                manualShutterNs = null
            }
        }
        cameraEngine.onHardwareShutterListener = { latestCapturePhoto() }
        cameraEngine.onGallerySaveStateListener = { state ->
            gallerySaveState = state
        }
        onDispose {
            cameraEngine.onTelemetryListener = null
            cameraEngine.onRawCapabilityListener = null
            cameraEngine.onFocalLengthsListener = null
            cameraEngine.onExposureCapabilitiesListener = null
            cameraEngine.onHardwareShutterListener = null
            cameraEngine.onPreviewConfigurationListener = null
            cameraEngine.onGallerySaveStateListener = null
        }
    }

    DisposableEffect(levelEnabled) {
        if (!levelEnabled) return@DisposableEffect onDispose { }
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val measuredRoll = Math.toDegrees(
                    kotlin.math.atan2(-event.values[0].toDouble(), event.values[1].toDouble())
                ).toFloat()
                deviceRoll = deviceRoll * .82f + measuredRoll * .18f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager?.unregisterListener(listener) }
    }

    val activePreset = presets.getOrElse(activePresetIndex) { presets[0] }
    val accentColor = Color(activePreset.accentColor)
    val latestPresets by rememberUpdatedState(presets)
    val latestActivePresetIndex by rememberUpdatedState(activePresetIndex)
    val latestGlView by rememberUpdatedState(glView)
    val latestCurrentZoom by rememberUpdatedState(currentZoom)
    val isoOptions = remember(exposureCapabilities) { isoStops(exposureCapabilities) }
    val shutterOptions = remember(exposureCapabilities) { shutterStops(exposureCapabilities) }
    val evOptions = remember { (-9..9).map { it / 3f } }

    LaunchedEffect(
        activePreset.id,
        activePreset.lightroom,
        activePreset.intensity,
        activePreset.grain,
        activePreset.halation,
        glView
    ) {
        applyPresetToPreview(glView, activePreset)
        cameraEngine.setCaptureFilter(activePreset)
    }

    LaunchedEffect(activePreset.id, rememberLastFilter) {
        cameraSettings.edit().apply {
            putBoolean("remember_last_filter", rememberLastFilter)
            if (rememberLastFilter) {
                putString("last_filter_id", activePreset.id)
            } else {
                remove("last_filter_id")
            }
        }.apply()
    }


    LaunchedEffect(glView, zebraMode, focusPeakingEnabled) {
        glView?.setAssistSettings(zebraMode, focusPeakingEnabled)
    }

    LaunchedEffect(glView, analysisEnabled) {
        glView?.setAnalysisEnabled(analysisEnabled)
        if (!analysisEnabled) previewAnalysis = PreviewAnalysis()
    }

    LaunchedEffect(selectedAspectRatio) {
        cameraEngine.captureAspectRatio = selectedAspectRatio
    }

    LaunchedEffect(
        zebraMode,
        focusPeakingEnabled,
        selectedAspectRatio,
        gridEnabled,
        levelEnabled,
        timerSeconds,
        analysisEnabled
    ) {
        cameraSettings.edit()
            .putInt("zebra_mode", zebraMode)
            .putBoolean("focus_peaking", focusPeakingEnabled)
            .putFloat("aspect_ratio", selectedAspectRatio)
            .putBoolean("grid", gridEnabled)
            .putBoolean("level", levelEnabled)
            .putInt("timer_seconds", timerSeconds)
            .putBoolean("analysis_enabled", analysisEnabled)
            .apply()
    }

    LaunchedEffect(showPresetSheet) {
        if (showPresetSheet) cameraEngine.pausePreviewStream() else cameraEngine.resumePreviewStream()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        backgroundFrame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(56.dp)
                    .alpha(0.24f)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            CameraAnalysisControlBar(
                analysis = previewAnalysis,
                analysisEnabled = analysisEnabled,
                shutterOptions = shutterOptions,
                selectedShutter = manualShutterNs ?: telemetry.exposureTimeNs,
                shutterManual = manualShutterNs != null,
                manualExposureSupported = exposureCapabilities.manualSensor,
                onShutterSelected = { value ->
                    manualShutterNs = value
                    cameraEngine.setManualExposureTime(value)
                },
                onShutterReset = {
                    manualShutterNs = null
                    cameraEngine.setManualExposureTime(null)
                },
                evOptions = evOptions,
                selectedEv = exposureCompensation,
                evManual = abs(exposureCompensation) > .01f,
                onEvSelected = { value ->
                    exposureCompensation = value
                    cameraEngine.setExposureCompensation(value)
                },
                onEvReset = {
                    exposureCompensation = 0f
                    cameraEngine.resetExposureCompensation()
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 1. FIXED LIVE PREVIEW (Centered 4:3 with Outer Padding & Rounded Corners - NEVER MOVES)
            Box(
                modifier = Modifier
                    .fillMaxWidth(.94f)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { position ->
                                if (size.width > 0 && size.height > 0) {
                                    cameraEngine.meterAndFocus(
                                        position.x / size.width,
                                        position.y / size.height
                                    )
                                    focusPoint = position
                                    focusSequence++
                                    hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            },
                            onDoubleTap = {
                                flashEnabled = false
                                manualIso = null
                                manualShutterNs = null
                                exposureCompensation = 0f
                                cameraEngine.toggleCamera()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (zoomChange != 1f) {
                                continuousZoom = (continuousZoom * zoomChange).coerceIn(0.5f, 8.0f)
                                val stepped = kotlin.math.round(continuousZoom * 10f) / 10f
                                if (stepped != latestCurrentZoom) {
                                    currentZoom = stepped
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                CameraPreviewGL(
                    modifier = Modifier.fillMaxSize(),
                    onSurfaceReady = { surfaceTexture ->
                        cameraEngine.openCamera(surfaceTexture, 960, 720)
                    },
                    onGLViewReady = { view ->
                        glView = view
                        applyPresetToPreview(view, activePreset)
                    },
                    onPreviewReady = { view ->
                        glView = view
                        applyPresetToPreview(view, activePreset)
                        cameraEngine.setCaptureFilter(activePreset)
                    },
                    onPreviewFrame = { bitmap ->
                        backgroundFrame = bitmap.asImageBitmap()
                    },
                    onPreviewAnalysis = { previewAnalysis = it }
                )
                CameraGuidesOverlay(
                    selectedAspectRatio = selectedAspectRatio,
                    gridEnabled = gridEnabled,
                    levelEnabled = levelEnabled,
                    deviceRoll = deviceRoll
                )
                focusPoint?.let { point ->
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawCircle(
                            color = accentColor,
                            radius = 27.dp.toPx(),
                            center = point,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.7f),
                            radius = 3.dp.toPx(),
                            center = point
                        )
                    }
                }
                if (previewBlackout) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black)
                    )
                }
                if (countdown > 0) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = .2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            countdown.toString(),
                            color = Color.White,
                            fontSize = 76.sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 2. MIDDLE SECTION: SHUTTER & TELEMETRY ROW / FILTER CAROUSEL
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .pointerInput(Unit) {
                        val shutterRadius = 48.dp.toPx()
                        val itemStride = 30.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (abs(down.position.x - size.width / 2f) > shutterRadius ||
                                abs(down.position.y - size.height / 2f) > shutterRadius
                            ) return@awaitEachGesture
                            down.consume()
                            shutterPressed = true
                            hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                            val releasedBeforeHold = withTimeoutOrNull(160L) {
                                var released = false
                                while (!released) {
                                    released = awaitPointerEvent().changes.none { it.pressed }
                                }
                                true
                            } ?: false

                            if (releasedBeforeHold) {
                                capturePhoto()
                                return@awaitEachGesture
                            }

                            val startIndex = latestActivePresetIndex
                            pendingPresetIndex = startIndex
                            uiMode = UiStateMode.FILTER_ACTIVE
                            var totalDrag = 0f
                            var lastCandidate = startIndex
                            var pressed = true
                            while (pressed) {
                                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                totalDrag += change.position.x - change.previousPosition.x
                                val candidate = (startIndex - (totalDrag / itemStride).roundToInt())
                                    .coerceIn(0, latestPresets.lastIndex)
                                if (candidate != lastCandidate) {
                                    pendingPresetIndex = candidate
                                    lastCandidate = candidate
                                    if (!latestPresets[candidate].isAddButton) {
                                        applyPresetToPreview(latestGlView, latestPresets[candidate])
                                    }
                                    hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                change.consume()
                                pressed = change.pressed
                            }

                            latestPresets.getOrNull(pendingPresetIndex)?.let { selected ->
                                if (selected.isAddButton) {
                                    pendingPresetIndex = latestActivePresetIndex
                                    showPresetSheet = true
                                } else {
                                    activePresetIndex = pendingPresetIndex
                                    applyPresetToPreview(latestGlView, selected)
                                    cameraEngine.setCaptureFilter(selected)
                                    presetLibrary.markRecent(selected.id)
                                    recentRevision++
                                }
                            }
                            shutterPressed = false
                            filterInteractionCounter++
                            uiMode = UiStateMode.IDLE
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (uiMode == UiStateMode.FILTER_ACTIVE) {
                    // FILTER CAROUSEL MODE (Image 2)
                    FilterCarouselRow(
                        presets = presets,
                        selectedIndex = pendingPresetIndex,
                        previewCache = previewCache,
                        shutterPressed = shutterPressed,
                        onSelectPreset = { index ->
                            val selected = presets[index]
                            if (selected.isAddButton) {
                                showPresetSheet = true
                            } else {
                                pendingPresetIndex = index
                                activePresetIndex = index
                                filterInteractionCounter++
                                applyPresetToPreview(glView, selected)
                                cameraEngine.setCaptureFilter(selected)
                                presetLibrary.markRecent(selected.id)
                                recentRevision++
                            }
                        },
                        onShutterClick = {
                            // Stable parent handles press, hold and release.
                        }
                    )
                } else {
                    // IDLE & ZOOM MODE (Image 1 & 3): Telemetry Bar on EXACT SAME HEIGHT as Shutter
                    IdleTelemetryShutterRow(
                        telemetry = telemetry,
                        shutterPressed = shutterPressed,
                        accentColor = accentColor,
                        captureFormat = cameraEngine.captureFormat,
                        rawCaptureSupported = rawCaptureSupported,
                        onFormatToggle = {
                            if (rawCaptureSupported) {
                                val nextFormat = when (cameraEngine.captureFormat) {
                                    "JPG" -> "RAW"
                                    "RAW" -> "RAW+JPG"
                                    else -> "JPG"
                                }
                                cameraEngine.captureFormat = nextFormat
                                telemetry = telemetry.copy(format = nextFormat)
                            }
                        },
                        isoOptions = isoOptions,
                        selectedIso = manualIso ?: telemetry.iso,
                        isoManual = manualIso != null,
                        manualIsoSupported = exposureCapabilities.manualSensor,
                        onIsoSelected = { value ->
                            manualIso = value
                            cameraEngine.setManualIso(value)
                        },
                        onIsoReset = {
                            manualIso = null
                            cameraEngine.setManualIso(null)
                        },
                        onSettingsClick = { showSettingsSheet = true },
                        onShutterClick = {
                            // Stable parent handles press, hold and release.
                        },
                        onShutterSwipe = {
                            // Replaced by press-and-hold selection.
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. BOTTOM SECTION: ZOOM ROCKER
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .pointerInput(Unit) {
                        var lastZoomStep = (latestCurrentZoom * 5f).roundToInt()
                        detectHorizontalDragGestures(
                            onDragStart = {
                                continuousZoom = latestCurrentZoom
                                lastZoomStep = (latestCurrentZoom * 5f).roundToInt()
                                uiMode = UiStateMode.ZOOM_ACTIVE
                                zoomInteractionCounter++
                            },
                            onDragEnd = { rockerMotion = 0f },
                            onDragCancel = { rockerMotion = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                rockerMotion = dragAmount.coerceIn(-28f, 28f)
                                continuousZoom = (continuousZoom - dragAmount * 0.0125f)
                                    .coerceIn(0.5f, 8.0f)
                                val nearestMilestone = listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f).minByOrNull {
                                    abs(it - continuousZoom)
                                }
                                val stepped = if (
                                    nearestMilestone != null && abs(nearestMilestone - continuousZoom) <= 0.14f
                                ) {
                                    nearestMilestone
                                } else {
                                    kotlin.math.round(continuousZoom * 100f) / 100f
                                }
                                val step = (stepped * 5f).roundToInt()
                                if (step != lastZoomStep) {
                                    lastZoomStep = step
                                    currentZoom = stepped
                                    zoomInteractionCounter++
                                    val isMilestone = listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f).any {
                                        abs(it - stepped) < 0.01f
                                    }
                                    hostView.performHapticFeedback(
                                        if (isMilestone) HapticFeedbackConstants.CONTEXT_CLICK
                                        else HapticFeedbackConstants.CLOCK_TICK
                                    )
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                AnimatedContent(
                    targetState = uiMode == UiStateMode.ZOOM_ACTIVE,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.72f))
                            .togetherWith(
                                fadeOut(tween(220)) + scaleOut(tween(260), targetScale = 0.72f)
                            )
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "zoomRockerExpansion"
                ) { expanded ->
                    if (expanded) {
                        GaussianZoomRocker(
                            currentZoom = smoothedZoom,
                            focalLengths = availableFocalLengths,
                            motionVelocity = rockerMotion
                        )
                    } else {
                        MinimalZoomIndicator(
                            currentZoom = smoothedZoom,
                            onClick = {
                                uiMode = UiStateMode.ZOOM_ACTIVE
                                zoomInteractionCounter++
                            }
                        )
                    }
                }

                Text(
                    text = "ϟ",
                    color = if (flashEnabled) accentColor else Color.White.copy(alpha = 0.75f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 22.dp, top = 5.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .springClickable(feedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                            flashEnabled = cameraEngine.toggleFlash()
                        }
                        .padding(top = 6.dp)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 22.dp, top = 5.dp)
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = galleryArrivalScale.value
                            scaleY = galleryArrivalScale.value
                        }
                        .clip(RoundedCornerShape(11.dp))
                        .springClickable(feedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                            if (gallerySaveState.pendingCount > 0) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "${if (gallerySaveState.pendingCount == 1) "Das Foto wird" else "Die Fotos werden"} noch entwickelt und ${if (gallerySaveState.pendingCount == 1) "erscheint" else "erscheinen"} gleich in der Galerie."
                                    )
                                }
                            } else if (!cameraEngine.openNativeGallery()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Noch keine gespeicherten Fotos.")
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    galleryThumbnail?.let { thumbnail ->
                        Image(
                            bitmap = thumbnail,
                            contentDescription = "Zuletzt aufgenommenes Foto",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .padding(3.dp)
                                .clip(RoundedCornerShape(9.dp))
                        )
                    } ?: Text(
                        text = "▣",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    )
                    if (gallerySaveState.pendingCount > 0) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(3.dp),
                            color = accentColor,
                            trackColor = Color.White.copy(alpha = .12f),
                            strokeWidth = 1.5.dp
                        )
                        if (gallerySaveState.pendingCount > 1) {
                            Text(
                                text = gallerySaveState.pendingCount.toString(),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .background(Color.Black.copy(alpha = .78f), CircleShape)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    } else if (showGalleryArrival) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-3).dp, y = 3.dp)
                                .size(9.dp)
                                .background(accentColor, CircleShape)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        ) { data ->
            Snackbar(
                containerColor = Color(0xFF252525),
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                    if (isImportingPreset) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF8AB4F8),
                            trackColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                }
            }
        }
    }

    if (showPresetSheet) {
        val allLibraryPresets = builtInPresets.filterNot(Preset::isAddButton) + customPresets
        val recentPresets = remember(builtInPresets, customPresets, recentRevision) {
            presetLibrary.recentPresets(allLibraryPresets)
        }
        ModalBottomSheet(
            // Let the sheet wrap its complete content. The scroll modifier below is
            // retained only as a compact-device/accessibility fallback.
            modifier = Modifier.fillMaxWidth(),
            onDismissRequest = { showPresetSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF101010),
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.62f),
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.42f))
            }
        ) {
            PresetLibrarySheet(
                trending = presetLibrary.trending,
                recent = recentPresets,
                custom = customPresets,
                selectedIds = selectedPresetIds,
                previewCache = previewCache,
                importing = isImportingPreset,
                onToggle = { preset ->
                    val wasSelected = preset.id in selectedPresetIds
                    val nextIds = if (wasSelected) selectedPresetIds - preset.id else selectedPresetIds + preset.id
                    selectedPresetIds = nextIds
                    presetLibrary.saveSelected(nextIds)
                    val activeId = presets.getOrNull(activePresetIndex)?.id
                    presets = buildShutterPresets(builtInPresets, customPresets, nextIds)
                    if (activeId == preset.id && wasSelected) {
                        activePresetIndex = presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
                        pendingPresetIndex = activePresetIndex
                        glView?.clearPreset()
                        cameraEngine.setCaptureFilter(presets[activePresetIndex])
                    } else {
                        activePresetIndex = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
                        pendingPresetIndex = activePresetIndex
                    }
                    hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                },
                onImport = {
                    scope.launch {
                        sheetState.hide()
                        showPresetSheet = false
                        lutPickerLauncher.launch(arrayOf("*/*"))
                    }
                },
                onEdit = { preset -> editingPreset = preset }
            )
        }
    }

    if (showSettingsSheet) {
        Dialog(
            onDismissRequest = { showSettingsSheet = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010), contentColor = Color.White) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showSettingsSheet = false }) {
                            Text("‹", fontSize = 34.sp, fontWeight = FontWeight.Light)
                        }
                        Text(
                            "Kamera-Einstellungen",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .08f))
                    CameraSettingsSheet(
                        zebraMode = zebraMode,
                        onZebraModeChange = { zebraMode = it },
                        focusPeakingEnabled = focusPeakingEnabled,
                        onFocusPeakingChange = { focusPeakingEnabled = it },
                        analysisEnabled = analysisEnabled,
                        onAnalysisEnabledChange = { analysisEnabled = it },
                        rememberLastFilter = rememberLastFilter,
                        onRememberLastFilterChange = { rememberLastFilter = it },
                        selectedAspectRatio = selectedAspectRatio,
                        onAspectRatioChange = { selectedAspectRatio = it },
                        gridEnabled = gridEnabled,
                        onGridChange = { gridEnabled = it },
                        levelEnabled = levelEnabled,
                        onLevelChange = { levelEnabled = it },
                        timerSeconds = timerSeconds,
                        onTimerChange = { timerSeconds = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    editingPreset?.let { preset ->
        LookEditorDialog(
            preset = preset,
            onDismiss = { editingPreset = null },
            onSave = { edited ->
                val saved = edited.copy(name = presetLibrary.uniqueName(edited.name, edited.id))
                presetLibrary.saveEdits(saved)
                previewCache.invalidate(saved.id)
                val activeId = presets.getOrNull(activePresetIndex)?.id
                builtInPresets = presetLibrary.builtInPresets()
                customPresets = presetLibrary.customPresets()
                presets = buildShutterPresets(builtInPresets, customPresets, selectedPresetIds)
                activePresetIndex = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
                pendingPresetIndex = activePresetIndex
                editingPreset = null
                hostView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        )
    }
}

@Composable
private fun PresetLibrarySheet(
    trending: List<Preset>,
    recent: List<Preset>,
    custom: List<Preset>,
    selectedIds: Set<String>,
    previewCache: FilterPreviewCache,
    importing: Boolean,
    onToggle: (Preset) -> Unit,
    onImport: () -> Unit,
    onEdit: (Preset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Filter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .09f)) {
                    Text(
                        "${selectedIds.size} aktiv",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = .76f)
                    )
                }
            }
            Text(
                "Antippen zum Aktivieren · lange halten zum Bearbeiten",
                modifier = Modifier.padding(top = 3.dp),
                color = Color.White.copy(alpha = .58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(7.dp))
        PresetSection("Trending", trending, selectedIds, previewCache, onToggle, onEdit)
        PresetSection("Zuletzt benutzt", recent, selectedIds, previewCache, onToggle, onEdit, emptyText = "Noch keine Filter benutzt")
        PresetSection("Von dir", custom, selectedIds, previewCache, onToggle, onEdit, emptyText = "Importierte Lightroom-Filter erscheinen hier")
        Button(
            onClick = onImport,
            enabled = !importing,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).heightIn(min = 54.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text(if (importing) "Presets werden importiert …" else "Lightroom-Presets importieren", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraSettingsSheet(
    zebraMode: Int,
    onZebraModeChange: (Int) -> Unit,
    focusPeakingEnabled: Boolean,
    onFocusPeakingChange: (Boolean) -> Unit,
    analysisEnabled: Boolean,
    onAnalysisEnabledChange: (Boolean) -> Unit,
    rememberLastFilter: Boolean,
    onRememberLastFilterChange: (Boolean) -> Unit,
    selectedAspectRatio: Float,
    onAspectRatioChange: (Float) -> Unit,
    gridEnabled: Boolean,
    onGridChange: (Boolean) -> Unit,
    levelEnabled: Boolean,
    onLevelChange: (Boolean) -> Unit,
    timerSeconds: Int,
    onTimerChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsSection(
            title = "Aufnahme & Verhalten",
            description = "Auslöser, Startverhalten und persönliche Vorgaben"
        ) {
            SettingSwitch(
                "Letzten Filter merken",
                "Beim nächsten Start wieder mit dem zuletzt ausgewählten Filter öffnen",
                rememberLastFilter,
                onRememberLastFilterChange
            )
            SettingsDivider()
            SettingGroup("Selbstauslöser") {
                SegmentedSelector(
                    options = listOf(0 to "Aus", 3 to "3 s", 10 to "10 s"),
                    selected = timerSeconds,
                    onSelected = onTimerChange
                )
            }
            Text(
                "Die Lautstärketasten lösen die Kamera ebenfalls aus und verwenden den gewählten Timer.",
                color = Color.White.copy(alpha = .48f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        SettingsSection(
            title = "Belichtung & Fokus",
            description = "Werkzeuge zur technischen Bildkontrolle"
        ) {
            SettingSwitch(
                "Histogramm & RGB-Waveform",
                "Belichtungsanalyse oberhalb des Suchers anzeigen",
                analysisEnabled,
                onAnalysisEnabledChange
            )
            SettingsDivider()
            SettingGroup("Zebra-Streifen") {
                SegmentedSelector(
                    options = listOf(0 to "Aus", 1 to "Leicht", 2 to "Intensiv"),
                    selected = zebraMode,
                    onSelected = onZebraModeChange
                )
            }
            SettingsDivider()
            SettingSwitch(
                "Fokus Peaking",
                "Markiert scharfe Kanten rot",
                focusPeakingEnabled,
                onFocusPeakingChange
            )
        }

        SettingsSection(
            title = "Komposition",
            description = "Ausschnitt, Raster und Ausrichtung"
        ) {
            SettingGroup("Seitenverhältnis") {
                SegmentedSelector(
                    options = listOf(
                        3f / 4f to "4:3",
                        2f / 3f to "3:2",
                        1f to "1:1",
                        9f / 16f to "16:9",
                        9f / 21f to "21:9"
                    ),
                    selected = selectedAspectRatio,
                    onSelected = onAspectRatioChange
                )
            }
            SettingsDivider()
            SettingSwitch("Fotogitter", "Drittelraster im Sucher", gridEnabled, onGridChange)
            SettingsDivider()
            SettingSwitch("Wasserwaage", "Horizontale Ausrichtung im Sucher", levelEnabled, onLevelChange)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .48f)
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = .055f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .07f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = .075f))
}

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = .72f))
        content()
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = Color.White.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> SegmentedSelector(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = .07f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelected(value) }
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) Color.Black else Color.White.copy(alpha = .72f),
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LookEditorDialog(
    preset: Preset,
    onDismiss: () -> Unit,
    onSave: (Preset) -> Unit
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var intensity by remember(preset.id) { mutableFloatStateOf(preset.intensity) }
    var grain by remember(preset.id) { mutableFloatStateOf(preset.grain) }
    var halation by remember(preset.id) { mutableFloatStateOf(preset.halation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("Look bearbeiten") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LookSlider("Intensität", intensity, { intensity = it })
                LookSlider("Grain", grain, { grain = it })
                LookSlider("Halation", halation, { halation = it })
                Text(
                    "Grain und Halation werden unabhängig vom importierten Preset angewendet.",
                    color = Color.White.copy(alpha = .52f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        preset.copy(
                            name = name.trim(),
                            intensity = intensity,
                            grain = grain,
                            halation = halation
                        )
                    )
                }
            ) { Text("Sichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun LookSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Medium)
            Text("${(value * 100).roundToInt()}%", fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = .64f))
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..1f)
    }
}

@Composable
private fun PresetSection(
    title: String,
    presets: List<Preset>,
    selectedIds: Set<String>,
    previewCache: FilterPreviewCache,
    onToggle: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    emptyText: String = ""
) {
    Column(modifier = Modifier.padding(bottom = 11.dp).animateContentSize()) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (presets.isEmpty()) {
            Text(emptyText, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), color = Color.White.copy(alpha = .48f), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(presets, key = { _, preset -> preset.id }) { _, preset ->
                    PresetLibraryCard(
                        preset = preset,
                        selected = preset.id in selectedIds,
                        previewCache = previewCache,
                        onClick = { onToggle(preset) },
                        onLongClick = { onEdit(preset) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetLibraryCard(
    preset: Preset,
    selected: Boolean,
    previewCache: FilterPreviewCache,
    cardSize: Dp = 108.dp,
    cornerRadius: Dp = 22.dp,
    labelSize: androidx.compose.ui.unit.TextUnit = 21.sp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val preview by produceState<FilterPreview?>(initialValue = null, preset.id) {
        value = previewCache.preview(preset)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) .94f else 1f,
        animationSpec = spring(dampingRatio = .62f, stiffness = 520f),
        label = "presetPress"
    )
    val selectedTint by animateFloatAsState(
        targetValue = if (selected) .22f else 0f,
        animationSpec = tween(220),
        label = "presetTint"
    )
    val shape = RoundedCornerShape(cornerRadius)
    val activeAccent = preview?.let { hdrBoost(Color(it.accentColor)) }
    Card(
        modifier = Modifier
            .size(cardSize)
            .then(
                if (selected && activeAccent != null) {
                    Modifier.shadow(
                        elevation = 9.dp,
                        shape = shape,
                        ambientColor = activeAccent.copy(alpha = .72f),
                        spotColor = activeAccent.copy(alpha = .92f)
                    )
                } else Modifier
            )
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        border = if (selected && preview != null) {
            androidx.compose.foundation.BorderStroke(2.5.dp, requireNotNull(activeAccent))
        } else null,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242424))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.bitmap.asImageBitmap(),
                    contentDescription = "Vorschau für ${preset.name}",
                    contentScale = ContentScale.Crop,
                    alpha = .5f,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (preview == null) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = .7f))
            }
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .12f)))
            preview?.let { filtered ->
                Box(
                    Modifier.matchParentSize().background(Color(filtered.accentColor).copy(alpha = selectedTint))
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .9f))
                        )
                    )
                    .padding(horizontal = if (cardSize < 70.dp) 3.dp else 8.dp, vertical = if (cardSize < 70.dp) 4.dp else 9.dp)
            ) {
                Text(
                    preset.name,
                    color = if (selected) HdrWhite else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (cardSize < 70.dp) 7.sp else 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium.copy(
                        shadow = if (selected && activeAccent != null) {
                            Shadow(color = activeAccent.copy(alpha = .9f), blurRadius = 8f)
                        } else null
                    )
                )
            }
        }
    }
}

@Composable
private fun CameraAnalysisControlBar(
    analysis: PreviewAnalysis,
    analysisEnabled: Boolean,
    shutterOptions: List<Long>,
    selectedShutter: Long,
    shutterManual: Boolean,
    manualExposureSupported: Boolean,
    onShutterSelected: (Long) -> Unit,
    onShutterReset: () -> Unit,
    evOptions: List<Float>,
    selectedEv: Float,
    evManual: Boolean,
    onEvSelected: (Float) -> Unit,
    onEvReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(76.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VerticalCameraWheel(
            label = "",
            values = shutterOptions,
            selectedValue = selectedShutter,
            formatter = ::formatExposureTime,
            distance = { a, b -> abs(a.toDouble() - b.toDouble()) },
            manual = shutterManual,
            enabled = manualExposureSupported,
            onSelected = onShutterSelected,
            onReset = onShutterReset,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (analysisEnabled) {
                HistogramView(
                    values = analysis.luminanceHistogram,
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (analysisEnabled) {
                RgbWaveformView(
                    analysis = analysis,
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                )
            }
        }
        VerticalCameraWheel(
            label = "EV",
            values = evOptions,
            selectedValue = selectedEv,
            formatter = { value -> if (abs(value) < .01f) "±0" else String.format(Locale.US, "%+.1f", value) },
            distance = { a, b -> abs(a - b).toDouble() },
            manual = evManual,
            onSelected = onEvSelected,
            onReset = onEvReset,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun HistogramView(values: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = .035f))
            .padding(6.dp)
    ) {
        if (values.isEmpty()) return@Canvas
        val barWidth = size.width / values.size
        values.forEachIndexed { index, value ->
            val height = value.coerceIn(0f, 1f) * size.height
            drawLine(
                color = Color.White.copy(alpha = .78f),
                start = Offset(index * barWidth, size.height),
                end = Offset(index * barWidth, size.height - height),
                strokeWidth = max(1f, barWidth)
            )
        }
    }
}

@Composable
private fun RgbWaveformView(analysis: PreviewAnalysis, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = .035f))
            .padding(6.dp)
    ) {
        fun drawChannel(values: FloatArray, color: Color) {
            if (values.size < 2) return
            for (index in 1 until values.size) {
                val x0 = (index - 1f) / (values.size - 1f) * size.width
                val x1 = index.toFloat() / (values.size - 1f) * size.width
                drawLine(
                    color = color.copy(alpha = .84f),
                    start = Offset(x0, size.height * (1f - values[index - 1].coerceIn(0f, 1f))),
                    end = Offset(x1, size.height * (1f - values[index].coerceIn(0f, 1f))),
                    strokeWidth = 1.15.dp.toPx()
                )
            }
        }
        drawChannel(analysis.redWaveform, Color(0xFFFF453A))
        drawChannel(analysis.greenWaveform, Color(0xFF32D74B))
        drawChannel(analysis.blueWaveform, Color(0xFF64D2FF))
    }
}

@Composable
private fun <T> VerticalCameraWheel(
    label: String,
    values: List<T>,
    selectedValue: T,
    formatter: (T) -> String,
    distance: (T, T) -> Double,
    manual: Boolean,
    enabled: Boolean = true,
    onSelected: (T) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return
    val view = LocalView.current
    val selectedIndex = values.indices.minByOrNull { distance(values[it], selectedValue) } ?: 0
    val latestIndex by rememberUpdatedState(selectedIndex)
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        accumulatedDrag += delta
        val threshold = 17f
        while (abs(accumulatedDrag) >= threshold) {
            val direction = if (accumulatedDrag > 0f) -1 else 1
            val next = (latestIndex + direction).coerceIn(0, values.lastIndex)
            if (next != latestIndex) {
                onSelected(values[next])
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            accumulatedDrag -= if (accumulatedDrag > 0f) threshold else -threshold
        }
    }
    val borderColor = if (manual) Color(0xFFFF3B30).copy(alpha = .78f) else Color.White.copy(alpha = .08f)
    Column(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = .035f))
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                enabled = enabled,
                onDragStopped = { accumulatedDrag = 0f }
            )
            .clickable(enabled = manual) { onReset() }
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = selectedIndex.takeIf { it > 0 }?.let { formatter(values[it - 1]) } ?: " ",
            color = Color.White.copy(alpha = .18f),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = formatter(values[selectedIndex]).let { value ->
                if (label.isBlank()) value else "$label $value"
            },
            color = if (!enabled) Color.White.copy(alpha = .34f) else if (manual) Color(0xFFFF3B30) else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = selectedIndex.takeIf { it < values.lastIndex }?.let { formatter(values[it + 1]) } ?: " ",
            color = Color.White.copy(alpha = .18f),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CameraGuidesOverlay(
    selectedAspectRatio: Float,
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    deviceRoll: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val previewRatio = size.width / size.height
        if (abs(selectedAspectRatio - previewRatio) > .01f) {
            if (selectedAspectRatio < previewRatio) {
                val contentWidth = size.height * selectedAspectRatio
                val side = (size.width - contentWidth) / 2f
                drawRect(Color.Black.copy(alpha = .72f), size = androidx.compose.ui.geometry.Size(side, size.height))
                drawRect(
                    Color.Black.copy(alpha = .72f),
                    topLeft = Offset(size.width - side, 0f),
                    size = androidx.compose.ui.geometry.Size(side, size.height)
                )
            } else {
                val contentHeight = size.width / selectedAspectRatio
                val top = (size.height - contentHeight) / 2f
                drawRect(Color.Black.copy(alpha = .72f), size = androidx.compose.ui.geometry.Size(size.width, top))
                drawRect(
                    Color.Black.copy(alpha = .72f),
                    topLeft = Offset(0f, size.height - top),
                    size = androidx.compose.ui.geometry.Size(size.width, top)
                )
            }
        }
        if (gridEnabled) {
            val gridColor = Color.White.copy(alpha = .42f)
            for (fraction in listOf(1f / 3f, 2f / 3f)) {
                drawLine(gridColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 1.dp.toPx())
                drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1.dp.toPx())
            }
        }
        if (levelEnabled) {
            val radians = Math.toRadians(deviceRoll.toDouble())
            val halfLength = 46.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val dx = (kotlin.math.cos(radians) * halfLength).toFloat()
            val dy = (kotlin.math.sin(radians) * halfLength).toFloat()
            val leveled = abs(deviceRoll) < 1.2f
            drawLine(
                color = if (leveled) Color(0xFF32D74B) else Color.White,
                start = Offset(center.x - dx, center.y - dy),
                end = Offset(center.x + dx, center.y + dy),
                strokeWidth = 2.dp.toPx()
            )
            drawCircle(
                color = if (leveled) Color(0xFF32D74B) else Color.White.copy(alpha = .72f),
                radius = 3.dp.toPx(),
                center = center
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// IDLE TELEMETRY + SHUTTER ROW (Image 1 & 3)
// -----------------------------------------------------------------------------------------
@Composable
fun IdleTelemetryShutterRow(
    telemetry: CameraEngine.TelemetryData,
    shutterPressed: Boolean,
    accentColor: Color,
    captureFormat: String,
    rawCaptureSupported: Boolean,
    onFormatToggle: () -> Unit,
    isoOptions: List<Int>,
    selectedIso: Int,
    isoManual: Boolean,
    manualIsoSupported: Boolean,
    onIsoSelected: (Int) -> Unit,
    onIsoReset: () -> Unit,
    onSettingsClick: () -> Unit,
    onShutterClick: () -> Unit,
    onShutterSwipe: () -> Unit
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (shutterPressed) .96f else 1f,
        animationSpec = spring(dampingRatio = .56f, stiffness = 650f),
        label = "shutterPress"
    )
    val shutterSwipeState = rememberDraggableState { delta ->
        if (abs(delta) > 8f) {
            onShutterSwipe()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VerticalCameraWheel(
            label = "ISO",
            values = isoOptions,
            selectedValue = selectedIso,
            formatter = Int::toString,
            distance = { a, b -> abs(a - b).toDouble() },
            manual = isoManual,
            enabled = manualIsoSupported,
            onSelected = onIsoSelected,
            onReset = onIsoReset,
            modifier = Modifier.weight(1f).height(76.dp)
        )

        // Left 2: Format (JPG / RAW / RAW+JPG)
        Text(
            text = captureFormat,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .then(if (rawCaptureSupported) Modifier.clickable { onFormatToggle() } else Modifier)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )

        // Center: Circular Shutter Button with Active Filter Accent Ring
        Box(
            modifier = Modifier
                .scale(shutterScale)
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, accentColor, CircleShape)
                .draggable(
                    state = shutterSwipeState,
                    orientation = Orientation.Horizontal
                )
                .clickable { onShutterClick() },
            contentAlignment = Alignment.Center
        ) {
            // Crisp Inner Shutter Core
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "⚙",
            fontSize = 23.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .springClickable(feedback = HapticFeedbackConstants.CLOCK_TICK) { onSettingsClick() }
        )
    }
}

// -----------------------------------------------------------------------------------------
// FILTER CAROUSEL ROW (Image 2 - Swiped Mode)
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterCarouselRow(
    presets: List<Preset>,
    selectedIndex: Int,
    previewCache: FilterPreviewCache,
    shutterPressed: Boolean,
    onSelectPreset: (Int) -> Unit,
    onShutterClick: () -> Unit
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (shutterPressed) .96f else 1f,
        animationSpec = spring(dampingRatio = .56f, stiffness = 650f),
        label = "filterShutterPress"
    )
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val filterSize = 48.dp
    val centerSidePadding = (screenWidth - filterSize) / 2

    LaunchedEffect(selectedIndex) {
        if (selectedIndex in presets.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LazyRow(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(horizontal = centerSidePadding),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(presets) { index, preset ->
                val isSelected = index == selectedIndex
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.0f else 0.86f,
                    animationSpec = tween(durationMillis = 180),
                    label = "filterScale"
                )

                if (preset.isAddButton) {
                    Box(
                        modifier = Modifier
                            .scale(scale)
                            .size(filterSize)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF1E2230))
                            .border(1.5.dp, Color(0xFF3B82F6), RoundedCornerShape(9.dp))
                            .clickable { onSelectPreset(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = Color(0xFF3B82F6), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(modifier = Modifier.scale(scale)) {
                        PresetLibraryCard(
                            preset = preset,
                            selected = isSelected,
                            previewCache = previewCache,
                            cardSize = filterSize,
                            cornerRadius = 10.dp,
                            labelSize = 10.sp,
                            onClick = { onSelectPreset(index) }
                        )
                    }
                }
            }
        }

        // The shutter is an independent overlay. Only the filters scroll underneath it,
        // so it remains geometrically centered for the entire interaction.
        val selectedPreset = presets.getOrElse(selectedIndex) { presets.first() }
        Box(
            modifier = Modifier
                .scale(shutterScale)
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, Color(selectedPreset.accentColor), CircleShape)
                .clickable { onShutterClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// MINIMAL ZOOM INDICATOR (Image 1 & 2)
// -----------------------------------------------------------------------------------------
@Composable
fun MinimalZoomIndicator(
    currentZoom: Float,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(top = 4.dp)
    ) {
        // Single minimal white tick mark
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color.White)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Current zoom level text (e.g. 1.3).
        Text(
            text = String.format(Locale.US, "%.1f", currentZoom),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

// -----------------------------------------------------------------------------------------
// GAUSSIAN ZOOM ROCKER DIAL (Image 3)
// -----------------------------------------------------------------------------------------
@Composable
fun GaussianZoomRocker(
    currentZoom: Float,
    focalLengths: List<CameraEngine.FocalLengthOption>,
    motionVelocity: Float = 0f
) {
    val zoomTicks = remember {
        buildList {
            add(0.5f)
            var value = 0.6f
            while (value <= 8.001f) {
                add(value)
                value += 0.2f
            }
        }
    }
    fun zoomToIndex(zoom: Float): Float = if (zoom <= 0.6f) {
        (zoom - 0.5f) / 0.1f
    } else {
        1f + (zoom - 0.6f) / 0.2f
    }
    val currentIndex = zoomToIndex(currentZoom)
    val focalLengthLabels = remember(focalLengths, zoomTicks) {
        focalLengths
            .groupBy { focalLength ->
                zoomTicks.indices.minByOrNull { index ->
                    abs(zoomTicks[index] - focalLength.zoomRatio)
                } ?: 0
            }
            .mapValues { (_, options) ->
                options.joinToString(" / ") { formatFocalLength(it.millimeters) }
            }
    }
    val motionTrail by animateFloatAsState(
        targetValue = motionVelocity,
        animationSpec = tween(180),
        label = "rockerMotionTrail"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Every zoom step gets a line; focal lengths are labels attached to their
        // nearest zoom line and do not replace the full scale.
        Canvas(modifier = Modifier.fillMaxSize()) {
                val centerPx = size.width / 2f
                val spacingPx = 10.dp.toPx()
                val tickAreaHeight = 42.dp.toPx()

                for (i in zoomTicks.indices) {
                    val distFromCenterIndex = i - currentIndex
                    val tickX = centerPx + distFromCenterIndex * spacingPx

                    // Clip ticks outside visible bounds
                    if (tickX < 0 || tickX > size.width) continue

                    // Keep the active zoom area prominent, while also giving every tick
                    // a visual bias towards the physical center of the rocker. This makes
                    // the center of the scale read as the visual anchor, even while it moves.
                    val absDist = abs(distFromCenterIndex.toFloat())
                    val sigma = 2.2f
                    val gaussianWeight = exp(- (absDist * absDist) / (2f * sigma * sigma))

                    val distanceFromScreenCenter = abs(tickX - centerPx)
                    val centerProximity = (
                        1f - distanceFromScreenCenter / (size.width / 2f)
                    ).coerceIn(0f, 1f)
                    val centerWeight = centerProximity * centerProximity
                    val visualWeight = (gaussianWeight * 0.72f + centerWeight * 0.28f)
                        .coerceIn(0f, 1f)

                    val baseHeight = 10.dp.toPx()
                    val maxHeight = 34.dp.toPx()
                    val tickHeight = baseHeight + (maxHeight - baseHeight) * visualWeight

                    // Edge alpha fade out
                    val distFromEdge = minOf(tickX, size.width - tickX)
                    val edgeFade = (distFromEdge / (size.width * 0.25f)).coerceIn(0f, 1f)

                    val strokeColor = Color.White.copy(
                        alpha = (0.16f + 0.72f * visualWeight) * edgeFade
                    )
                    val strokeWidth = (1.1f + 0.8f * visualWeight).dp.toPx()

                    if (abs(motionTrail) > 0.5f) {
                        for (trail in 1..3) {
                            val trailX = tickX - motionTrail * 0.42f * trail
                            if (trailX in 0f..size.width) {
                                drawLine(
                                    color = strokeColor.copy(alpha = strokeColor.alpha * (0.18f / trail)),
                                    start = Offset(trailX, (tickAreaHeight - tickHeight) / 2f),
                                    end = Offset(trailX, (tickAreaHeight + tickHeight) / 2f),
                                    strokeWidth = strokeWidth
                                )
                            }
                        }
                    }

                    drawLine(
                        color = strokeColor,
                        start = Offset(tickX, (tickAreaHeight - tickHeight) / 2f),
                        end = Offset(tickX, (tickAreaHeight + tickHeight) / 2f),
                        strokeWidth = strokeWidth
                    )
                }

                // Fixed indicator: the scale can stop between two 0.2 steps.
                drawLine(
                    color = Color(0xFFFF3B30),
                    start = Offset(centerPx, 4.dp.toPx()),
                    end = Offset(centerPx, 38.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx()
                )

                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 11.sp.toPx()
                }
                focalLengthLabels.forEach { (tickIndex, label) ->
                    val labelX = centerPx + (tickIndex - currentIndex) * spacingPx
                    if (labelX < 0f || labelX > size.width) return@forEach
                    val distFromEdge = minOf(labelX, size.width - labelX)
                    val edgeFade = (distFromEdge / (size.width * 0.2f)).coerceIn(0f, 1f)
                    labelPaint.color = 0xFFFFFFFF.toInt()
                    labelPaint.alpha = (180 * edgeFade).roundToInt()
                    labelPaint.typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.NORMAL
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        labelX,
                        60.dp.toPx(),
                        labelPaint
                    )
                }
        }
    }
}
