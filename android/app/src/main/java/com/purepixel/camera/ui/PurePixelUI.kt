package com.purepixel.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import androidx.lifecycle.LifecycleEventObserver
import com.purepixel.camera.camera.CameraEngine
import com.purepixel.camera.R
import com.purepixel.camera.gl.CameraPreviewGL
import com.purepixel.camera.gl.GLCameraView
import com.purepixel.camera.tracking.TrackingState
import com.purepixel.camera.gl.PreviewAnalysis
import com.purepixel.camera.model.Preset
import com.purepixel.camera.model.LightroomPreset
import com.purepixel.camera.model.FilterPreviewCache
import com.purepixel.camera.model.FilterPreview
import com.purepixel.camera.model.PresetLibrary
import com.purepixel.camera.model.PresetCategory
import com.purepixel.camera.video.FilteredVideoRecorder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
    ZOOM_ACTIVE,   // Expanded expressive lens selector below shutter
    FILTER_ACTIVE  // Image 2: Swipe on shutter opens Filter Carousel, auto-fades back after 2s
}

private enum class CaptureMode { PHOTO, VIDEO }
private enum class VideoRecordingState { IDLE, PREPARING, RECORDING, STOPPING }

private enum class ZoomDisplayUnit { RATIO, MILLIMETERS }

private const val MaxActiveFilters = 10
private const val ZoomDragSensitivity = .0075f

private data class MagneticZoomResult(
    val zoom: Float,
    val activeMilestone: Float?
)

/**
 * Pulls the zoom progressively towards a physical lens without creating the broad,
 * flat dead zone of a hard snap. A small lock radius keeps the exact lens value
 * stable, while the wider release radius supplies enough hysteresis to avoid chatter.
 */
private fun magneticZoom(
    rawZoom: Float,
    activeMilestone: Float?,
    milestones: List<Float>
): MagneticZoomResult {
    if (milestones.isEmpty()) return MagneticZoomResult(rawZoom, null)

    fun captureRadius(point: Float): Float = max(.035f, point * .035f).coerceAtMost(.16f)

    val heldMilestone = activeMilestone
        ?.takeIf { held ->
            milestones.any { abs(it - held) < .001f } &&
                abs(rawZoom - held) <= captureRadius(held) * 1.8f
        }
    val nearestMilestone = milestones.minByOrNull { abs(it - rawZoom) }
    val milestone = heldMilestone ?: nearestMilestone?.takeIf {
        abs(rawZoom - it) <= captureRadius(it)
    } ?: return MagneticZoomResult(rawZoom, null)

    val capture = captureRadius(milestone)
    val distance = abs(rawZoom - milestone)
    val lockRadius = capture * .22f
    if (distance <= lockRadius) return MagneticZoomResult(milestone, milestone)

    val releaseRadius = capture * 1.8f
    val proximity = (1f - distance / releaseRadius).coerceIn(0f, 1f)
    val attraction = proximity * proximity * .72f
    return MagneticZoomResult(
        zoom = rawZoom + (milestone - rawZoom) * attraction,
        activeMilestone = milestone
    )
}

/** Processing options are always available; only selectable looks consume the carousel limit. */
private fun limitedFilterSelection(ids: Set<String>): Set<String> = buildSet {
    ids.asSequence()
        .filter { it !in Preset.FIXED_QUICK_PRESET_IDS }
        .take(MaxActiveFilters)
        .forEach(::add)
}

private fun activeFilterCount(ids: Set<String>): Int = ids.count { it !in Preset.FIXED_QUICK_PRESET_IDS }

private fun buildShutterPresets(
    builtIns: List<Preset>,
    custom: List<Preset>,
    selectedIds: Set<String>
): List<Preset> {
    val selectableBuiltIns = builtIns.filterNot(Preset::isAddButton)
    val limitedIds = limitedFilterSelection(selectedIds)
    val fixed = selectableBuiltIns.filter { it.id in Preset.FIXED_QUICK_PRESET_IDS }
    val selected = (selectableBuiltIns + custom).filter { it.id in limitedIds }
    return fixed + selected + builtIns.first(Preset::isAddButton)
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

private fun formatZoomValue(
    zoom: Float,
    equivalentFocalLength: Float?,
    unit: ZoomDisplayUnit
): String = when (unit) {
    ZoomDisplayUnit.RATIO -> String.format(Locale.getDefault(), "%.1f×", zoom)
    ZoomDisplayUnit.MILLIMETERS -> equivalentFocalLength?.roundToInt()?.toString()
        ?: String.format(Locale.getDefault(), "%.1f×", zoom)
}

private val HdrWhite = Color.White
/** Near-black surfaces keep the blue reserved for active controls. */
private val AccentBlue = Color(0xFF1C85F3)
private val AppSurface = Color(0xFF05070A)
private val CardSurface = Color(0xFF0A0F15)
private val ElevatedSurface = Color(0xFF101821)
private val DividerColor = Color(0xFF17202A)
private val OutlineBlue = Color(0xFF243140)
private val TelemetrySurface = CardSurface
private val QuietWhite = Color(0xFFFFFFFF)
/** Darkest base canvas for sheets, settings, menus, and the filter library. */
private val PanelBlack = AppSurface
private val RaisedBlack = ElevatedSurface
private const val ExpressiveSpringDamping = .82f
private const val ExpressiveSpringStiffness = 420f

/**
 * Keeps portrait-locked camera controls upright while the device turns. The target
 * is accumulated so crossing 0/360 degrees always animates through the short path.
 */
// Observe values inside the effect: live telemetry must not recompose the camera
// screen on every animation frame. Each new value gives the spring a small impulse.
private fun Modifier.cameraValueMotion(
    strength: Float = 1f,
    value: () -> Any?
): Modifier = composed {
    val latestValue by rememberUpdatedState(value)
    val impulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { latestValue() }.drop(1).collectLatest {
            impulse.animateTo(1f, tween(55))
            impulse.animateTo(0f, spring(dampingRatio = .62f, stiffness = 480f))
        }
    }
    graphicsLayer {
        val motion = impulse.value * strength
        scaleX = 1f + .018f * motion
        scaleY = 1f - .012f * motion
        rotationZ = .8f * motion
    }
}

@Composable
private fun rememberUprightControlRotation(deviceOrientationDegrees: Int): Float {
    var previousOrientation by remember { mutableIntStateOf(deviceOrientationDegrees) }
    var rotationTarget by remember { mutableFloatStateOf(-deviceOrientationDegrees.toFloat()) }
    LaunchedEffect(deviceOrientationDegrees) {
        val clockwiseDelta = (deviceOrientationDegrees - previousOrientation + 360) % 360
        val shortestDelta = if (clockwiseDelta > 180) clockwiseDelta - 360 else clockwiseDelta
        rotationTarget -= shortestDelta
        previousOrientation = deviceOrientationDegrees
    }
    return animateFloatAsState(
        targetValue = rotationTarget,
        animationSpec = spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness
        ),
        label = "uprightCameraControls"
    ).value
}

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
        animationSpec = spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness
        ),
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
    onFirstPreviewFrame: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val scope = rememberCoroutineScope()
    val presetLibrary = remember { PresetLibrary(context.applicationContext) }
    val previewCache = remember { FilterPreviewCache(context.applicationContext) }
    LaunchedEffect(lifecycleOwner, previewCache) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { previewCache.clearMemory() }
            }
        }
    }
    val cameraSettings = remember {
        context.applicationContext.getSharedPreferences("camera_settings", android.content.Context.MODE_PRIVATE)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val editorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val quickControlsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var telemetry by remember { mutableStateOf(CameraEngine.TelemetryData()) }
    var exposureCapabilities by remember { mutableStateOf(cameraEngine.exposureCapabilities) }
    var rawCaptureSupported by remember { mutableStateOf(cameraEngine.supportsRawCapture) }
    var glView by remember { mutableStateOf<GLCameraView?>(null) }
    var subjectTracking by remember { mutableStateOf(TrackingState()) }
    DisposableEffect(glView) {
        val view = glView
        view?.setTrackingListener { state ->
            subjectTracking = state
            state.box?.let { cameraEngine.updateTrackedFocus(it.x, it.y) }
            if (state.box == null && !state.searching) cameraEngine.stopTrackedFocus()
        }
        onDispose {
            view?.stopObjectTracking()
            view?.setTrackingListener { }
            cameraEngine.stopTrackedFocus()
        }
    }
    LaunchedEffect(subjectTracking.lost) {
        if (subjectTracking.lost) {
            delay(1800L)
            subjectTracking = TrackingState()
        }
    }
    val videoRecorder = remember { FilteredVideoRecorder(context.applicationContext) }
    var captureMode by rememberSaveable { mutableStateOf(CaptureMode.PHOTO) }
    var videoRecordingState by remember { mutableStateOf(VideoRecordingState.IDLE) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var videoStartRequest by remember { mutableIntStateOf(0) }
    var includeAudioForNextVideo by remember { mutableStateOf(false) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        includeAudioForNextVideo = granted
        videoStartRequest++
    }
    var firstPreviewReady by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(CameraEngine.FlashMode.OFF) }
    var whiteBalanceMode by remember {
        mutableStateOf(
            runCatching {
                CameraEngine.WhiteBalanceMode.valueOf(
                    cameraSettings.getString("white_balance_mode", CameraEngine.WhiteBalanceMode.AUTO.name)
                        ?: CameraEngine.WhiteBalanceMode.AUTO.name
                )
            }.getOrDefault(CameraEngine.WhiteBalanceMode.AUTO)
        )
    }
    var zoomDisplayUnit by remember {
        mutableStateOf(
            runCatching {
                ZoomDisplayUnit.valueOf(
                    cameraSettings.getString("zoom_display_unit", ZoomDisplayUnit.RATIO.name)
                        ?: ZoomDisplayUnit.RATIO.name
                )
            }.getOrDefault(ZoomDisplayUnit.RATIO)
        )
    }
    var manualIso by remember { mutableStateOf<Int?>(null) }
    var manualShutterNs by remember { mutableStateOf<Long?>(null) }
    var exposureCompensation by remember { mutableFloatStateOf(0f) }
    var previewBlackout by remember { mutableStateOf(false) }
    var blackoutDurationMs by remember { mutableLongStateOf(80L) }
    var shutterSequence by remember { mutableIntStateOf(0) }
    var shutterPressed by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusSequence by remember { mutableIntStateOf(0) }

    // Keep first composition cheap. Parsing the packaged XMP library and scanning
    // imported files is deferred until the camera has delivered its first frame.
    var builtInPresets by remember { mutableStateOf(presetLibrary.startupPresets()) }
    var customPresets by remember { mutableStateOf(emptyList<Preset>()) }
    var rememberLastFilter by remember {
        mutableStateOf(cameraSettings.getBoolean("remember_last_filter", false))
    }
    val defaultSelectedIds = remember { Preset.DEFAULT_SELECTED_PRESET_IDS }
    var selectedPresetIds by remember {
        mutableStateOf(limitedFilterSelection(presetLibrary.selectedIds(defaultSelectedIds)))
    }
    var presets by remember {
        mutableStateOf(buildShutterPresets(builtInPresets, customPresets, selectedPresetIds))
    }
    val startupPresetId = remember {
        if (cameraSettings.getBoolean("remember_last_filter", false)) {
            cameraSettings.getString("last_filter_id", "no_filter") ?: "no_filter"
        } else {
            "no_filter"
        }
    }
    var activePresetIndex by remember {
        mutableIntStateOf(
            presets.indexOfFirst { it.id == startupPresetId }
                .takeIf { it >= 0 }
                ?: presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
        )
    }
    var pendingPresetIndex by remember { mutableIntStateOf(activePresetIndex) }
    var uiMode by remember { mutableStateOf(UiStateMode.IDLE) }
    var showQuickControlsSheet by remember { mutableStateOf(false) }
    var showPresetSheet by remember { mutableStateOf(false) }
    var draftPresetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var demoMode by rememberSaveable { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<Preset?>(null) }
    var isImportingPreset by remember { mutableStateOf(false) }
    val openPresetLibrary = {
        draftPresetIds = selectedPresetIds
        showPresetSheet = true
    }
    val applyFilterSelection: (Set<String>) -> Unit = { requestedIds ->
        val nextIds = limitedFilterSelection(requestedIds)
        val activeId = presets.getOrNull(activePresetIndex)?.id
        selectedPresetIds = nextIds
        presetLibrary.saveSelected(nextIds)
        presets = buildShutterPresets(builtInPresets, customPresets, nextIds)
        if (activeId !in Preset.FIXED_QUICK_PRESET_IDS && activeId !in nextIds) {
            activePresetIndex = presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
            pendingPresetIndex = activePresetIndex
            glView?.clearPreset()
            cameraEngine.setCaptureFilter(presets[activePresetIndex])
        } else {
            activePresetIndex = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            pendingPresetIndex = activePresetIndex
        }
    }
    var gallerySaveState by remember { mutableStateOf(CameraEngine.GallerySaveState()) }
    val galleryArrivalScale = remember { Animatable(1f) }
    var showGalleryArrival by remember { mutableStateOf(false) }
    var galleryThumbnail by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var previewAnalysis by remember { mutableStateOf(PreviewAnalysis()) }
    var analysisEnabled by remember { mutableStateOf(cameraSettings.getBoolean("analysis_enabled", false)) }
    var shutterControlVisible by remember {
        mutableStateOf(cameraSettings.getBoolean("shutter_control_visible", false))
    }
    var isoControlVisible by remember {
        mutableStateOf(cameraSettings.getBoolean("iso_control_visible", false))
    }
    var evControlVisible by remember {
        mutableStateOf(cameraSettings.getBoolean("ev_control_visible", false))
    }
    var zebraMode by remember { mutableIntStateOf(cameraSettings.getInt("zebra_mode", 0)) }
    var focusPeakingEnabled by remember { mutableStateOf(cameraSettings.getBoolean("focus_peaking", false)) }
    var selectedAspectRatio by remember { mutableFloatStateOf(cameraSettings.getFloat("aspect_ratio", 3f / 4f)) }
    var gridEnabled by remember { mutableStateOf(cameraSettings.getBoolean("grid", false)) }
    var levelEnabled by remember { mutableStateOf(cameraSettings.getBoolean("level", false)) }
    var timerSeconds by remember { mutableIntStateOf(cameraSettings.getInt("timer_seconds", 0)) }
    var fullResolutionJpeg by remember {
        mutableStateOf(cameraSettings.getBoolean("full_resolution_jpeg", false))
    }
    var countdown by remember { mutableIntStateOf(0) }
    var deviceRoll by remember { mutableFloatStateOf(0f) }
    var deviceOrientationDegrees by remember {
        mutableIntStateOf(cameraEngine.deviceOrientationDegrees)
    }
    val uprightControlRotation = rememberUprightControlRotation(deviceOrientationDegrees)
    
    // Camera2 capabilities vary substantially by vendor. The engine owns both the
    // focal labels and the confirmed zoom range so the rocker cannot advertise a
    // value that the active camera silently clamps.
    var availableFocalLengths by remember { mutableStateOf(cameraEngine.availableFocalLengths) }
    var zoomState by remember { mutableStateOf(cameraEngine.currentZoomState) }
    var currentZoom by remember { mutableFloatStateOf(cameraEngine.currentZoomRatio) }
    var continuousZoom by remember { mutableFloatStateOf(currentZoom) }
    var isZoomDragging by remember { mutableStateOf(false) }
    var activeZoomMilestone by remember { mutableStateOf<Float?>(null) }
    var zoomEndStopDirection by remember { mutableIntStateOf(0) }
    var filterInteractionCounter by remember { mutableIntStateOf(0) }
    var zoomInteractionCounter by remember { mutableIntStateOf(0) }
    val latestIsZoomDragging by rememberUpdatedState(isZoomDragging)
    val zoomMilestones = remember(
        availableFocalLengths,
        zoomState.minimumRatio,
        zoomState.maximumRatio
    ) {
        availableFocalLengths
            .asSequence()
            .map { it.zoomRatio }
            .filter { it.isFinite() && it >= zoomState.minimumRatio && it <= zoomState.maximumRatio }
            .distinctBy { (it * 1000f).roundToInt() }
            .sorted()
            .toList()
    }

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
                    val nextIds = limitedFilterSelection(selectedPresetIds + importedPresets.map(Preset::id))
                    customPresets = updatedCustom
                    selectedPresetIds = nextIds
                    presetLibrary.saveSelected(nextIds)
                    presets = buildShutterPresets(builtInPresets, updatedCustom, nextIds)
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
                val result = cameraEngine.triggerCapture()
                if (result.accepted) {
                    blackoutDurationMs = result.blackoutDurationMs
                    shutterSequence++
                    hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        }
    }
    val latestCapturePhoto by rememberUpdatedState(capturePhoto)

    val startVideoRecording: () -> Unit = start@{
        if (videoRecordingState != VideoRecordingState.IDLE) return@start
        val targetView = glView ?: return@start
        scope.launch {
            videoRecordingState = VideoRecordingState.PREPARING
            if (timerSeconds > 0) {
                for (remaining in timerSeconds downTo 1) {
                    countdown = remaining
                    hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    delay(1000L)
                }
                countdown = 0
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    videoRecorder.prepare(includeAudioForNextVideo)
                    val surface = requireNotNull(videoRecorder.inputSurface)
                    targetView.attachRecordingSurface(
                        surface,
                        FilteredVideoRecorder.VIDEO_WIDTH,
                        FilteredVideoRecorder.VIDEO_HEIGHT
                    ).getOrThrow()
                    videoRecorder.start()
                    targetView.setRecordingFrames(true)
                }
            }
            if (outcome.isSuccess) {
                recordingStartedAt = SystemClock.elapsedRealtime()
                recordingSeconds = 0L
                videoRecordingState = VideoRecordingState.RECORDING
                hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } else {
                withContext(Dispatchers.IO) {
                    targetView.setRecordingFrames(false)
                    targetView.detachRecordingSurface()
                    videoRecorder.discard()
                }
                videoRecordingState = VideoRecordingState.IDLE
                snackbarHostState.showSnackbar("Die Videoaufnahme konnte nicht gestartet werden.")
            }
        }
    }

    val stopVideoRecording: () -> Unit = stop@{
        if (videoRecordingState != VideoRecordingState.RECORDING) return@stop
        val targetView = glView
        scope.launch {
            videoRecordingState = VideoRecordingState.STOPPING
            val saved = withContext(Dispatchers.IO) {
                targetView?.setRecordingFrames(false)
                targetView?.detachRecordingSurface()
                videoRecorder.stopAndPublish()
            }
            videoRecordingState = VideoRecordingState.IDLE
            recordingSeconds = 0L
            hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            snackbarHostState.showSnackbar(
                if (saved != null) "Video gespeichert." else "Das Video konnte nicht gespeichert werden."
            )
        }
    }

    val toggleVideoRecording: () -> Unit = {
        when (videoRecordingState) {
            VideoRecordingState.IDLE -> {
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasAudioPermission) {
                    includeAudioForNextVideo = true
                    startVideoRecording()
                } else {
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            VideoRecordingState.RECORDING -> stopVideoRecording()
            VideoRecordingState.PREPARING, VideoRecordingState.STOPPING -> Unit
        }
    }
    val latestToggleVideoRecording by rememberUpdatedState(toggleVideoRecording)

    LaunchedEffect(videoStartRequest) {
        if (videoStartRequest > 0) startVideoRecording()
    }

    LaunchedEffect(captureMode) {
        glView?.stopObjectTracking()
        cameraEngine.setVideoMode(captureMode == CaptureMode.VIDEO)
    }

    LaunchedEffect(videoRecordingState, recordingStartedAt) {
        while (videoRecordingState == VideoRecordingState.RECORDING) {
            recordingSeconds = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L
            delay(250L)
        }
    }

    // Collapse only after the gesture has ended; a stationary finger still owns the rocker.
    LaunchedEffect(uiMode, isZoomDragging, zoomInteractionCounter) {
        if (uiMode == UiStateMode.ZOOM_ACTIVE && !isZoomDragging) {
            delay(450)
            uiMode = UiStateMode.IDLE
        }
    }

    // Keep the last "page" visible for a moment after the finger is lifted. This
    // lets the spring motion settle and also leaves time to tap another card.
    LaunchedEffect(uiMode, shutterPressed, filterInteractionCounter) {
        if (uiMode == UiStateMode.FILTER_ACTIVE && !shutterPressed) {
            delay(1100)
            if (!shutterPressed) uiMode = UiStateMode.IDLE
        }
    }

    LaunchedEffect(shutterSequence) {
        if (shutterSequence > 0) {
            previewBlackout = true
            delay(blackoutDurationMs.coerceIn(28L, 48L))
            previewBlackout = false
        }
    }

    LaunchedEffect(focusSequence) {
        if (focusSequence > 0) {
            delay(1800)
            focusPoint = null
        }
    }

    LaunchedEffect(firstPreviewReady) {
        if (!firstPreviewReady) return@LaunchedEffect
        // Camera startup owns the critical path. Only after its first frame do we
        // scan imported files and parse the 37 packaged Lightroom XMP presets.
        delay(120L)
        val (loadedBuiltIns, loadedCustom) = withContext(Dispatchers.IO) {
            presetLibrary.builtInPresets() to presetLibrary.customPresets()
        }
        val currentActiveId = presets.getOrNull(activePresetIndex)?.id ?: "no_filter"
        val targetActiveId = if (filterInteractionCounter == 0 && rememberLastFilter) {
            startupPresetId
        } else {
            currentActiveId
        }
        val loadedShutterPresets = buildShutterPresets(
            loadedBuiltIns,
            loadedCustom,
            selectedPresetIds
        )
        builtInPresets = loadedBuiltIns
        customPresets = loadedCustom
        presets = loadedShutterPresets
        activePresetIndex = loadedShutterPresets.indexOfFirst { it.id == targetActiveId }
            .takeIf { it >= 0 }
            ?: loadedShutterPresets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
        pendingPresetIndex = activePresetIndex
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


    LaunchedEffect(firstPreviewReady) {
        if (!firstPreviewReady) return@LaunchedEffect
        delay(220L)
        galleryThumbnail = withContext(Dispatchers.IO) {
            cameraEngine.loadLatestGalleryThumbnail()
        }?.asImageBitmap()
    }

    LaunchedEffect(gallerySaveState.failedRevision) {
        if (gallerySaveState.failedRevision > 0) {
            snackbarHostState.showSnackbar("Das Foto konnte nicht in der Galerie gespeichert werden.")
        }
    }

    // Apply the zoom value from the gesture directly. The previous spring animation
    // made both the dial and the camera preview visibly trail the user's finger.
    LaunchedEffect(currentZoom) {
        glView?.stopObjectTracking()
        cameraEngine.setZoomRatio(currentZoom)
    }
    val latestCaptureMode by rememberUpdatedState(captureMode)
    val latestVideoRecordingState by rememberUpdatedState(videoRecordingState)
    val latestStopVideoRecording by rememberUpdatedState(stopVideoRecording)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_PAUSE &&
                latestVideoRecordingState == VideoRecordingState.RECORDING
            ) {
                latestStopVideoRecording()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Listen to real-time camera telemetry and the active camera's physical lenses.
    DisposableEffect(Unit) {
        cameraEngine.onPreviewConfigurationListener = {
            hostView.post { glView?.stopObjectTracking() }
        }
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
        cameraEngine.onZoomStateListener = { confirmed ->
            zoomState = confirmed
            currentZoom = confirmed.ratio
            if (!latestIsZoomDragging) continuousZoom = confirmed.ratio
        }
        zoomState = cameraEngine.currentZoomState
        currentZoom = zoomState.ratio
        continuousZoom = zoomState.ratio
        exposureCapabilities = cameraEngine.exposureCapabilities
        cameraEngine.onExposureCapabilitiesListener = { capabilities ->
            exposureCapabilities = capabilities
            if (!capabilities.manualSensor) {
                manualIso = null
                manualShutterNs = null
            }
        }
        cameraEngine.onHardwareShutterListener = {
            if (latestCaptureMode == CaptureMode.VIDEO) latestToggleVideoRecording()
            else latestCapturePhoto()
        }
        cameraEngine.onGallerySaveStateListener = { state ->
            gallerySaveState = state
        }
        deviceOrientationDegrees = cameraEngine.deviceOrientationDegrees
        cameraEngine.onDeviceOrientationListener = { orientation ->
            deviceOrientationDegrees = orientation
        }
        onDispose {
            cameraEngine.onTelemetryListener = null
            cameraEngine.onRawCapabilityListener = null
            cameraEngine.onFocalLengthsListener = null
            cameraEngine.onZoomStateListener = null
            cameraEngine.onExposureCapabilitiesListener = null
            cameraEngine.onHardwareShutterListener = null
            cameraEngine.onPreviewConfigurationListener = null
            cameraEngine.onGallerySaveStateListener = null
            cameraEngine.onDeviceOrientationListener = null
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
    val accentColor = AccentBlue
    val latestPresets by rememberUpdatedState(presets)
    val latestActivePresetIndex by rememberUpdatedState(activePresetIndex)
    val latestUiMode by rememberUpdatedState(uiMode)
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

    // Warm the small card bitmaps and the live-preview LUTs before the filter book
    // is opened. Both paths run off the main thread, so the first page turn does
    // not have to pay the one-time rendering cost.
    LaunchedEffect(presets, firstPreviewReady) {
        if (!firstPreviewReady) return@LaunchedEffect
        delay(300L)
        presets.asSequence()
            .filterNot(Preset::isAddButton)
            .take(10)
            .forEach { previewCache.preview(it) }
    }

    LaunchedEffect(glView, presets, firstPreviewReady) {
        if (!firstPreviewReady) return@LaunchedEffect
        delay(300L)
        glView?.preloadPresets(
            presets.asSequence()
                .filterNot(Preset::isAddButton)
                .take(10)
                .toList()
        )
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
        analysisEnabled,
        shutterControlVisible,
        isoControlVisible,
        evControlVisible,
        fullResolutionJpeg,
        whiteBalanceMode,
        zoomDisplayUnit
    ) {
        cameraSettings.edit()
            .putInt("zebra_mode", zebraMode)
            .putBoolean("focus_peaking", focusPeakingEnabled)
            .putFloat("aspect_ratio", selectedAspectRatio)
            .putBoolean("grid", gridEnabled)
            .putBoolean("level", levelEnabled)
            .putInt("timer_seconds", timerSeconds)
            .putBoolean("analysis_enabled", analysisEnabled)
            .putBoolean("shutter_control_visible", shutterControlVisible)
            .putBoolean("iso_control_visible", isoControlVisible)
            .putBoolean("ev_control_visible", evControlVisible)
            .putBoolean("full_resolution_jpeg", fullResolutionJpeg)
            .putString("white_balance_mode", whiteBalanceMode.name)
            .putString("zoom_display_unit", zoomDisplayUnit.name)
            .apply()
    }

    LaunchedEffect(whiteBalanceMode, firstPreviewReady) {
        cameraEngine.setWhiteBalanceMode(whiteBalanceMode)
    }

    LaunchedEffect(fullResolutionJpeg) {
        cameraEngine.setJpegResolutionMode(
            if (fullResolutionJpeg) CameraEngine.JpegResolutionMode.FULL_SENSOR
            else CameraEngine.JpegResolutionMode.STANDARD_12_MP
        )
    }

    LaunchedEffect(showPresetSheet, showSettingsSheet, glView) {
        val paused = showPresetSheet || showSettingsSheet
        glView?.setCameraStreamActive(!paused)
        if (paused) cameraEngine.pausePreviewStream() else cameraEngine.resumePreviewStream()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val hasAnalysisControls = shutterControlVisible || isoControlVisible || evControlVisible || analysisEnabled
            // Reserve the complete control stack before sizing the 4:3 viewfinder.
            // Keep the same bounds while opening the filter book or expanding zoom.
            val standardPreviewWidth = minOf(
                (maxWidth - 32.dp).coerceAtLeast(0.dp),
                (maxHeight - 282.dp).coerceAtLeast(0.dp) * .75f
            )
            val standardTopInset = ((maxHeight - standardPreviewWidth / .75f - 250.dp) / 2)
                .coerceIn(16.dp, 32.dp)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(if (hasAnalysisControls) 24.dp else standardTopInset))
                if (hasAnalysisControls) {
                    CameraAnalysisControlBar(
                        analysis = { previewAnalysis },
                        analysisEnabled = analysisEnabled,
                        shutterControlVisible = shutterControlVisible,
                        isoControlVisible = isoControlVisible,
                        evControlVisible = evControlVisible,
                        controlRotation = uprightControlRotation,
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
                }
                // 1. FIXED LIVE PREVIEW (Centered 4:3 with Outer Padding & Rounded Corners - NEVER MOVES)
                Box(
                    modifier = Modifier
                        .then(
                            if (hasAnalysisControls) Modifier.fillMaxWidth(.94f)
                            else Modifier.width(standardPreviewWidth)
                        )
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { position ->
                                    if (size.width > 0 && size.height > 0) {
                                        glView?.stopObjectTracking()
                                        cameraEngine.meterAndFocus(
                                            position.x / size.width,
                                            position.y / size.height
                                        )
                                        if (!demoMode) glView?.startObjectTracking(
                                            position.x / size.width, position.y / size.height
                                        )
                                        focusPoint = position
                                        focusSequence++
                                        hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                },
                                onDoubleTap = {
                                    if (latestVideoRecordingState == VideoRecordingState.IDLE) {
                                        flashMode = CameraEngine.FlashMode.OFF
                                        manualIso = null
                                        manualShutterNs = null
                                        exposureCompensation = 0f
                                        glView?.stopObjectTracking()
                                        cameraEngine.toggleCamera()
                                    }
                                }
                            )
                        }
                        .pointerInput(zoomState.minimumRatio, zoomState.maximumRatio) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (zoomChange != 1f) {
                                    continuousZoom = (continuousZoom * zoomChange).coerceIn(
                                        zoomState.minimumRatio,
                                        zoomState.maximumRatio
                                    )
                                    val stepped = kotlin.math.round(continuousZoom * 10f) / 10f
                                    if (stepped != latestCurrentZoom) {
                                        currentZoom = stepped.coerceIn(zoomState.minimumRatio, zoomState.maximumRatio)
                                        uiMode = UiStateMode.ZOOM_ACTIVE
                                        zoomInteractionCounter++
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
                        onFirstPreviewFrame = {
                            firstPreviewReady = true
                            onFirstPreviewFrame()
                        },
                        onPreviewAnalysis = { previewAnalysis = it }
                    )
                    if (demoMode) {
                        Image(
                            painter = painterResource(R.drawable.demo_preview),
                            contentDescription = "Beispielbild im Demo-Modus",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                    CameraGuidesOverlay(
                        selectedAspectRatio = selectedAspectRatio,
                        gridEnabled = gridEnabled,
                        levelEnabled = levelEnabled,
                        deviceRoll = { deviceRoll }
                    )
                    subjectTracking.box?.let { target ->
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawRect(
                                color = accentColor,
                                topLeft = Offset((target.x - target.width / 2) * size.width,
                                    (target.y - target.height / 2) * size.height),
                                size = androidx.compose.ui.geometry.Size(target.width * size.width,
                                    target.height * size.height),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                    if (subjectTracking.lost) {
                        Text(
                            text = stringResource(R.string.tracking_lost),
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                                .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    focusPoint?.takeIf { subjectTracking.box == null }?.let { point ->
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
                                .background(Color.Black.copy(alpha = .86f))
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
                            )
                        }
                    }
                }

                // Standard view: 24 dp between visible edges. Account for the quick
                // row's 6 dp inset, shutter shelf's 9 dp inset and zoom's 8 dp inset.
                Spacer(modifier = Modifier.height(18.dp))
                CaptureQuickControlRow(
                    captureMode = captureMode,
                    flashMode = flashMode,
                    timerSeconds = timerSeconds,
                    controlRotation = uprightControlRotation,
                    modeSwitchEnabled = videoRecordingState == VideoRecordingState.IDLE,
                    onCaptureModeChange = { mode ->
                        if (videoRecordingState == VideoRecordingState.IDLE) {
                            captureMode = mode
                            uiMode = UiStateMode.IDLE
                        }
                    },
                    onSwitchCamera = {
                        if (videoRecordingState == VideoRecordingState.IDLE) {
                            flashMode = CameraEngine.FlashMode.OFF
                            manualIso = null
                            manualShutterNs = null
                            exposureCompensation = 0f
                            glView?.stopObjectTracking()
                            cameraEngine.toggleCamera()
                        }
                    },
                    onFlashClick = {
                        if (videoRecordingState == VideoRecordingState.IDLE) {
                            flashMode = cameraEngine.cycleFlashMode()
                        }
                    },
                    onTimerClick = {
                        if (videoRecordingState == VideoRecordingState.IDLE) {
                            timerSeconds = when (timerSeconds) {
                                0 -> 3
                                3 -> 10
                                else -> 0
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(if (hasAnalysisControls) 12.dp else 9.dp))

                // 2. MIDDLE SECTION: SHUTTER & TELEMETRY ROW / FILTER CAROUSEL
                val shutterShelfHeight by animateDpAsState(
                    // Match the carousel exactly: the former extra 2 dp created a visible
                    // black strip beneath the cards while the shelf was animating.
                    targetValue = if (uiMode == UiStateMode.FILTER_ACTIVE) 106.dp else 90.dp,
                    animationSpec = spring(
                        dampingRatio = ExpressiveSpringDamping,
                        stiffness = ExpressiveSpringStiffness
                    ),
                    label = "shutterShelfHeight"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(shutterShelfHeight)
                        // The card stack may extend past its measured bounds while it tilts.
                        // Keep it above the zoom shelf so no lower layer can cut it off.
                        .zIndex(2f)
                        .pointerInput(Unit) {
                            val shutterRadius = 48.dp.toPx()
                            val itemStride = 37.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val filterAlreadyActive = latestUiMode == UiStateMode.FILTER_ACTIVE
                                val outsideShutter =
                                    abs(down.position.x - size.width / 2f) > shutterRadius ||
                                        abs(down.position.y - size.height / 2f) > shutterRadius
                                if (!filterAlreadyActive && outsideShutter) return@awaitEachGesture

                                if (latestCaptureMode == CaptureMode.VIDEO) {
                                    down.consume()
                                    shutterPressed = true
                                    hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    var pressed = true
                                    while (pressed) {
                                        pressed = awaitPointerEvent().changes.any { it.pressed }
                                    }
                                    shutterPressed = false
                                    latestToggleVideoRecording()
                                    return@awaitEachGesture
                                }

                                shutterPressed = true
                                if (!filterAlreadyActive) {
                                    down.consume()
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
                                } else {
                                    // Any point across the open book can start the next swipe.
                                    filterInteractionCounter++
                                }

                                val startIndex = latestActivePresetIndex
                                pendingPresetIndex = startIndex
                                uiMode = UiStateMode.FILTER_ACTIVE
                                var totalDrag = 0f
                                var lastCandidate = startIndex
                                var pressed = true
                                while (pressed) {
                                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                    val dragDelta = change.position.x - change.previousPosition.x
                                    totalDrag += dragDelta
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
                                    if (!filterAlreadyActive || abs(totalDrag) > 4.dp.toPx()) {
                                        change.consume()
                                    }
                                    pressed = change.pressed
                                }

                                latestPresets.getOrNull(pendingPresetIndex)?.let { selected ->
                                    if (selected.isAddButton) {
                                        pendingPresetIndex = latestActivePresetIndex
                                        openPresetLibrary()
                                    } else {
                                        activePresetIndex = pendingPresetIndex
                                        applyPresetToPreview(latestGlView, selected)
                                        cameraEngine.setCaptureFilter(selected)
                                        presetLibrary.markRecent(selected.id)
                                    }
                                }
                                shutterPressed = false
                                filterInteractionCounter++
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Never keep the idle shelf and the filter book in the composition at
                    // the same time. AnimatedContent briefly retained its outgoing layer,
                    // which allowed the black shutter surface to cover the cards at both
                    // ends of the transition.
                    if (uiMode == UiStateMode.FILTER_ACTIVE) {
                            FilterCarouselRow(
                                presets = presets,
                                selectedIndex = pendingPresetIndex,
                                previewCache = previewCache,
                                shutterPressed = shutterPressed,
                                onSelectPreset = { index ->
                                    val selected = presets[index]
                                    if (selected.isAddButton) {
                                        openPresetLibrary()
                                    } else {
                                        pendingPresetIndex = index
                                        activePresetIndex = index
                                        filterInteractionCounter++
                                        applyPresetToPreview(glView, selected)
                                        cameraEngine.setCaptureFilter(selected)
                                        presetLibrary.markRecent(selected.id)
                                    }
                                }
                            )
                    } else {
                            // IDLE & ZOOM MODE (Image 1 & 3): Telemetry Bar on EXACT SAME HEIGHT as Shutter
                            IdleTelemetryShutterRow(
                                shutterPressed = shutterPressed,
                                accentColor = accentColor,
                                captureMode = captureMode,
                                videoRecordingState = videoRecordingState,
                                recordingSeconds = recordingSeconds,
                                controlRotation = uprightControlRotation,
                                onQuickControlsClick = { showQuickControlsSheet = true },
                                galleryThumbnail = galleryThumbnail,
                                galleryArrivalScale = galleryArrivalScale.value,
                                galleryPendingCount = gallerySaveState.pendingCount,
                                showGalleryArrival = showGalleryArrival,
                                onGalleryClick = {
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
                                onShutterClick = {
                                    // Stable parent handles press, hold and release.
                                },
                                onShutterSwipe = {
                                    // Replaced by press-and-hold selection.
                                }
                            )
                    }
                }

                // The filter shelf owns its full vertical space; no gap should flash below it.
                if (uiMode != UiStateMode.FILTER_ACTIVE) {
                    Spacer(modifier = Modifier.height(if (hasAnalysisControls) 8.dp else 7.dp))
                }

                // 3. BOTTOM SECTION: ZOOM ROCKER
                val zoomRockerEnabled = uiMode != UiStateMode.FILTER_ACTIVE
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (hasAnalysisControls) 90.dp else 78.dp)
                        // Zoom controls are irrelevant while leafing through filters and can
                        // otherwise overlap the lower edge of the 3D card stack.
                        .then(
                            if (!zoomRockerEnabled) {
                                Modifier
                            } else {
                                Modifier.pointerInput(
                                    zoomState.minimumRatio,
                                    zoomState.maximumRatio,
                                    zoomMilestones
                                ) {
                                    var lastZoomStep = (latestCurrentZoom * 5f).roundToInt()
                                    var lastEndStop = 0
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            continuousZoom = latestCurrentZoom
                                            activeZoomMilestone = null
                                            lastZoomStep = (latestCurrentZoom * 5f).roundToInt()
                                            lastEndStop = 0
                                            isZoomDragging = true
                                            uiMode = UiStateMode.ZOOM_ACTIVE
                                            zoomInteractionCounter++
                                        },
                                        onDragEnd = {
                                            isZoomDragging = false
                                            if (uiMode == UiStateMode.ZOOM_ACTIVE) uiMode = UiStateMode.IDLE
                                            activeZoomMilestone = null
                                            zoomEndStopDirection = 0
                                            zoomInteractionCounter++
                                        },
                                        onDragCancel = {
                                            isZoomDragging = false
                                            if (uiMode == UiStateMode.ZOOM_ACTIVE) uiMode = UiStateMode.IDLE
                                            activeZoomMilestone = null
                                            zoomEndStopDirection = 0
                                            zoomInteractionCounter++
                                        },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()

                                            val proposedZoom = continuousZoom * exp(-dragAmount / density * ZoomDragSensitivity)
                                            val endStop = when {
                                                proposedZoom < zoomState.minimumRatio -> -1
                                                proposedZoom > zoomState.maximumRatio -> 1
                                                else -> 0
                                            }
                                            zoomEndStopDirection = endStop
                                            if (endStop != 0 && endStop != lastEndStop) {
                                                hostView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                            }
                                            lastEndStop = endStop

                                            continuousZoom = proposedZoom.coerceIn(
                                                zoomState.minimumRatio,
                                                zoomState.maximumRatio
                                            )
                                            val previousMilestone = activeZoomMilestone
                                            val magnetic = magneticZoom(
                                                rawZoom = continuousZoom,
                                                activeMilestone = previousMilestone,
                                                milestones = zoomMilestones
                                            )
                                            activeZoomMilestone = magnetic.activeMilestone
                                            val displayedZoom = (kotlin.math.round(magnetic.zoom * 100f) / 100f).coerceIn(zoomState.minimumRatio, zoomState.maximumRatio)
                                            val step = (displayedZoom * 5f).roundToInt()
                                            currentZoom = displayedZoom

                                            if (magnetic.activeMilestone != null && previousMilestone == null) {
                                                zoomInteractionCounter++
                                                hostView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            } else if (step != lastZoomStep) {
                                                zoomInteractionCounter++
                                                hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                            lastZoomStep = step
                                        }
                                    )
                                }
                            }
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (zoomRockerEnabled) {
                        ZoomPillDial(
                            expanded = uiMode == UiStateMode.ZOOM_ACTIVE,
                            currentZoom = currentZoom,
                            focalLengths = availableFocalLengths,
                            minimumZoom = zoomState.minimumRatio,
                            maximumZoom = zoomState.maximumRatio,
                            equivalentFocalLength = zoomState.equivalentFocalLengthMillimeters,
                            displayUnit = zoomDisplayUnit,
                            endStopDirection = zoomEndStopDirection,
                            onSelectZoom = { ratio ->
                                currentZoom = ratio.coerceIn(zoomState.minimumRatio, zoomState.maximumRatio)
                                continuousZoom = currentZoom
                                uiMode = UiStateMode.ZOOM_ACTIVE
                                zoomInteractionCounter++
                                hostView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                        )
                    }

                }

            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        ) { data ->
            Snackbar(
                containerColor = PanelBlack,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                    if (isImportingPreset) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentBlue,
                            trackColor = Color.White.copy(alpha = 0.14f)
                        )
                    }
                }
            }
        }
    }

    if (showQuickControlsSheet) {
        QuickControlsSheet(
            sheetState = quickControlsSheetState,
            captureFormat = cameraEngine.captureFormat,
            rawCaptureSupported = rawCaptureSupported,
            onFormatToggle = {
                if (rawCaptureSupported) {
                    val nextFormat = when (cameraEngine.captureFormat) {
                        "JPG" -> "RAW"
                        "RAW" -> "RAW+JPG"
                        else -> "JPG"
                    }
                    cameraEngine.setCaptureFormat(nextFormat)
                    telemetry = telemetry.copy(format = nextFormat)
                }
            },
            onOpenFilterLibrary = {
                showQuickControlsSheet = false
                openPresetLibrary()
            },
            onOpenSettings = {
                showQuickControlsSheet = false
                showSettingsSheet = true
            },
            onDismiss = { showQuickControlsSheet = false }
        )
    }

    if (showPresetSheet) {
        Dialog(
            onDismissRequest = { showPresetSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = PanelBlack, contentColor = Color.White) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = PanelBlack,
                    contentColor = Color.White,
                    topBar = {
                        LibraryHeader(
                            title = "Filter",
                            selectedCount = activeFilterCount(draftPresetIds),
                            onClose = { showPresetSheet = false },
                            modifier = Modifier.statusBarsPadding()
                        )
                    },
                    bottomBar = {
                        FilterActionBar(
                            onCancel = { showPresetSheet = false },
                            onApply = {
                                applyFilterSelection(draftPresetIds)
                                showPresetSheet = false
                                hostView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            },
                            modifier = Modifier.navigationBarsPadding()
                        )
                    }
                ) { contentPadding ->
                    PresetLibrarySheet(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                        builtIns = builtInPresets,
                        custom = customPresets,
                        selectedIds = draftPresetIds,
                        previewCache = previewCache,
                        onImportLightroom = {
                            showPresetSheet = false
                            lutPickerLauncher.launch(arrayOf("*/*"))
                        },
                        onToggle = { preset ->
                            val wasSelected = preset.id in draftPresetIds
                            val atLimit = !wasSelected && preset.id !in Preset.FIXED_QUICK_PRESET_IDS &&
                                activeFilterCount(draftPresetIds) >= MaxActiveFilters
                            if (atLimit) {
                                scope.launch { snackbarHostState.showSnackbar("Maximal $MaxActiveFilters aktive Filter") }
                                hostView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            } else {
                                draftPresetIds = limitedFilterSelection(
                                    if (wasSelected) draftPresetIds - preset.id else draftPresetIds + preset.id
                                )
                                hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                        onEdit = { preset ->
                            // Modal sheets must be attached to the camera window, not behind
                            // the full-screen library dialog.
                            showPresetSheet = false
                            editingPreset = preset
                        }
                    )
                }
            }
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
            Surface(modifier = Modifier.fillMaxSize(), color = PanelBlack, contentColor = Color.White) {
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
                            Icon(
                                painter = painterResource(R.drawable.ic_pixel_chevron_left),
                                contentDescription = "Zurück",
                                tint = QuietWhite,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            "Kamera-Einstellungen",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .combinedClickable(
                                onClick = { showInfoSheet = true },
                                onLongClick = {
                                    demoMode = !demoMode
                                    hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (demoMode) "Demo-Modus aktiviert" else "Demo-Modus deaktiviert"
                                        )
                                    }
                                }
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pixel_info),
                                contentDescription = "Informationen; gedrückt halten für Demo-Modus",
                                tint = QuietWhite
                            )
                        }
                    }
                    HorizontalDivider(color = DividerColor)
                    CameraSettingsSheet(
                        zebraMode = zebraMode,
                        onZebraModeChange = { zebraMode = it },
                        focusPeakingEnabled = focusPeakingEnabled,
                        onFocusPeakingChange = { focusPeakingEnabled = it },
                        analysisEnabled = analysisEnabled,
                        onAnalysisEnabledChange = { analysisEnabled = it },
                        shutterControlVisible = shutterControlVisible,
                        onShutterControlVisibleChange = { shutterControlVisible = it },
                        isoControlVisible = isoControlVisible,
                        onIsoControlVisibleChange = { isoControlVisible = it },
                        evControlVisible = evControlVisible,
                        onEvControlVisibleChange = { evControlVisible = it },
                        rememberLastFilter = rememberLastFilter,
                        onRememberLastFilterChange = { rememberLastFilter = it },
                        selectedAspectRatio = selectedAspectRatio,
                        onAspectRatioChange = { selectedAspectRatio = it },
                        gridEnabled = gridEnabled,
                        onGridChange = { gridEnabled = it },
                        levelEnabled = levelEnabled,
                        onLevelChange = { levelEnabled = it },
                        zoomDisplayUnit = zoomDisplayUnit,
                        onZoomDisplayUnitChange = { zoomDisplayUnit = it },
                        fullResolutionJpeg = fullResolutionJpeg,
                        onFullResolutionJpegChange = { fullResolutionJpeg = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showInfoSheet) {
        Dialog(
            onDismissRequest = { showInfoSheet = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = PanelBlack, contentColor = Color.White) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showInfoSheet = false }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pixel_chevron_left),
                                contentDescription = "Zurück",
                                tint = QuietWhite,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            "Über hooru",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(30.dp))
                    Text(
                        "Fotografieren mit Kontrolle",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "hooru verbindet einen direkten Kamera-Workflow mit Live-Filtern und manueller Belichtungssteuerung.",
                        color = QuietWhite.copy(alpha = .76f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Tipp: Halte das Info-Symbol in den Kamera-Einstellungen gedrückt, um den Demo-Modus mit dem Beispielbild ein- oder auszuschalten.",
                        color = AccentBlue.copy(alpha = .94f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    editingPreset?.let { preset ->
        LookEditorSheet(
            preset = preset,
            onDismiss = { editingPreset = null },
            sheetState = editorSheetState,
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
            },
            onDelete = if (preset.isCustom) {
                {
                    presetLibrary.deleteCustomPreset(preset)
                    previewCache.invalidate(preset.id)
                    selectedPresetIds = selectedPresetIds - preset.id
                    presetLibrary.saveSelected(selectedPresetIds)
                    customPresets = presetLibrary.customPresets()
                    val activeId = presets.getOrNull(activePresetIndex)?.id
                    presets = buildShutterPresets(builtInPresets, customPresets, selectedPresetIds)
                    activePresetIndex = presets.indexOfFirst { it.id == activeId }
                        .takeIf { it >= 0 }
                        ?: presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
                    pendingPresetIndex = activePresetIndex
                    editingPreset = null
                    hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } else null
        )
    }
}

@Composable
private fun PresetLibrarySheet(
    modifier: Modifier = Modifier,
    builtIns: List<Preset>,
    custom: List<Preset>,
    selectedIds: Set<String>,
    previewCache: FilterPreviewCache,
    onImportLightroom: () -> Unit,
    onToggle: (Preset) -> Unit,
    onEdit: (Preset) -> Unit
) {
    val categoryPresets = remember(builtIns) {
        PresetCategory.entries.map { category ->
            category to builtIns
                .filter { !it.isAddButton && it.id !in Preset.FIXED_QUICK_PRESET_IDS && it.category == category }
                .sortedBy(Preset::name)
        }
    }
    val allGroups = categoryPresets.map { it.first.label to it.second } + listOf("Von dir" to custom)
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(PresetCategory.WARM.name) }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "selection-summary") {
            FilterSelectionSummary(
                groups = allGroups,
                selectedIds = selectedIds,
                onRemoveGroup = { presets ->
                    presets.filter { it.id in selectedIds }.forEach(onToggle)
                },
                onClearAll = {
                    allGroups.flatMap { it.second }.filter { it.id in selectedIds }.forEach(onToggle)
                }
            )
        }
        items(categoryPresets, key = { it.first.name }) { (category, presets) ->
            PresetAccordionSection(
                groupKey = category.name,
                title = category.label,
                presets = presets,
                selectedIds = selectedIds,
                previewCache = previewCache,
                expanded = expandedGroup == category.name,
                onExpandedChange = { expandedGroup = if (expandedGroup == category.name) null else category.name },
                onToggle = onToggle,
                onEdit = onEdit
            )
        }
        item(key = "custom") {
            PresetAccordionSection(
                groupKey = "custom",
                title = "Von dir",
                presets = custom,
                selectedIds = selectedIds,
                previewCache = previewCache,
                expanded = expandedGroup == "custom",
                onExpandedChange = { expandedGroup = if (expandedGroup == "custom") null else "custom" },
                onToggle = onToggle,
                onEdit = onEdit,
                emptyText = "Importierte Lightroom-Filter erscheinen hier",
                onImport = onImportLightroom
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    title: String,
    selectedCount: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_pixel_chevron_left),
                    contentDescription = "Zurück",
                    tint = QuietWhite,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AccentBlue.copy(alpha = .16f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = .8f))
            ) {
                Text(
                    "$selectedCount von $MaxActiveFilters gewählt",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    color = HdrWhite,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        HorizontalDivider(color = DividerColor, thickness = 1.dp)
    }
}

@Composable
private fun FilterSelectionSummary(
    groups: List<Pair<String, List<Preset>>>,
    selectedIds: Set<String>,
    onRemoveGroup: (List<Preset>) -> Unit,
    onClearAll: () -> Unit
) {
    val selectedGroups = groups.filter { (_, presets) -> presets.any { it.id in selectedIds } }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = TelemetrySurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBlue)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Auswahl",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onClearAll,
                    enabled = selectedGroups.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Alles löschen", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (selectedGroups.isEmpty()) {
                Text(
                    "Noch keine Filter gewählt",
                    color = QuietWhite.copy(alpha = .62f),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                selectedGroups.chunked(2).forEach { rowGroups ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowGroups.forEach { (label, presets) ->
                            val count = presets.count { it.id in selectedIds }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .clickable { onRemoveGroup(presets) },
                                shape = RoundedCornerShape(12.dp),
                                color = AccentBlue.copy(alpha = .16f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = .72f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "$label · $count",
                                        color = HdrWhite,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Text("×", color = HdrWhite, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (rowGroups.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (activeFilterCount(selectedIds) >= MaxActiveFilters) {
                Text(
                    "Auswahl-Limit erreicht: Entferne einen Filter, um weitere Varianten zu wählen.",
                    color = QuietWhite.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun FilterActionBar(
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = PanelBlack,
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBlue),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuietWhite)
                ) { Text("Abbrechen", fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .weight(1.28f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White)
                ) { Text("Anwenden", fontWeight = FontWeight.Bold) }
            }
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
    shutterControlVisible: Boolean,
    onShutterControlVisibleChange: (Boolean) -> Unit,
    isoControlVisible: Boolean,
    onIsoControlVisibleChange: (Boolean) -> Unit,
    evControlVisible: Boolean,
    onEvControlVisibleChange: (Boolean) -> Unit,
    rememberLastFilter: Boolean,
    onRememberLastFilterChange: (Boolean) -> Unit,
    selectedAspectRatio: Float,
    onAspectRatioChange: (Float) -> Unit,
    gridEnabled: Boolean,
    onGridChange: (Boolean) -> Unit,
    levelEnabled: Boolean,
    onLevelChange: (Boolean) -> Unit,
    zoomDisplayUnit: ZoomDisplayUnit,
    onZoomDisplayUnitChange: (ZoomDisplayUnit) -> Unit,
    fullResolutionJpeg: Boolean,
    onFullResolutionJpegChange: (Boolean) -> Unit,
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
            title = stringResource(R.string.settings_capture),
            description = "Auslöser, Startverhalten und persönliche Vorgaben"
        ) {
            SettingSwitch(
                title = stringResource(R.string.remember_filter),
                description = "",
                checked = rememberLastFilter,
                onCheckedChange = onRememberLastFilterChange
            )
            SettingsDivider()
            SettingGroup("Auflösung") {
                SegmentedSelector(
                    options = listOf(false to "12 MP", true to "Voll"),
                    selected = fullResolutionJpeg,
                    onSelected = onFullResolutionJpegChange
                )
            }
            SettingsDivider()
            SettingGroup("Zoom-Anzeige") {
                SegmentedSelector(
                    options = listOf(
                        ZoomDisplayUnit.RATIO to "Faktor",
                        ZoomDisplayUnit.MILLIMETERS to "mm"
                    ),
                    selected = zoomDisplayUnit,
                    onSelected = onZoomDisplayUnitChange
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_exposure_focus),
            description = "Werkzeuge zur technischen Bildkontrolle"
        ) {
            SettingSwitch(
                stringResource(R.string.show_shutter_control),
                stringResource(R.string.show_shutter_control_description),
                shutterControlVisible,
                onShutterControlVisibleChange
            )
            SettingsDivider()
            SettingSwitch(
                stringResource(R.string.show_iso_control),
                stringResource(R.string.show_iso_control_description),
                isoControlVisible,
                onIsoControlVisibleChange
            )
            SettingsDivider()
            SettingSwitch(
                stringResource(R.string.show_ev_control),
                stringResource(R.string.show_ev_control_description),
                evControlVisible,
                onEvControlVisibleChange
            )
            SettingsDivider()
            SettingSwitch(
                stringResource(R.string.histogram),
                stringResource(R.string.histogram_description),
                analysisEnabled,
                onAnalysisEnabledChange
            )
            SettingsDivider()
            SettingGroup(stringResource(R.string.zebra)) {
                SegmentedSelector(
                    options = listOf(0 to "Aus", 1 to "Leicht", 2 to "Intensiv"),
                    selected = zebraMode,
                    onSelected = onZebraModeChange
                )
            }
            SettingsDivider()
            SettingSwitch(
                stringResource(R.string.focus_peaking),
                stringResource(R.string.focus_peaking_description),
                focusPeakingEnabled,
                onFocusPeakingChange
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_composition),
            description = "Ausschnitt, Raster und Ausrichtung"
        ) {
            SettingGroup(stringResource(R.string.aspect_ratio)) {
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
            SettingSwitch(stringResource(R.string.grid), stringResource(R.string.grid_description), gridEnabled, onGridChange)
            SettingsDivider()
            SettingSwitch(stringResource(R.string.level), stringResource(R.string.level_description), levelEnabled, onLevelChange)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = .78f)
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = TelemetrySurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBlue)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = DividerColor)
}

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            modifier = Modifier.weight(.34f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = .62f)
        )
        Column(
            modifier = Modifier.weight(.66f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = .9f))
            if (description.isNotBlank()) {
                Text(description, color = Color.White.copy(alpha = .43f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentBlue,
                checkedTrackColor = ElevatedSurface,
                checkedBorderColor = OutlineBlue,
                uncheckedThumbColor = Color.White.copy(alpha = .72f),
                uncheckedTrackColor = ElevatedSurface,
                uncheckedBorderColor = OutlineBlue
            )
        )
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
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedSurface)
            .border(1.dp, OutlineBlue, RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val borderColor by animateColorAsState(if (active) AccentBlue else Color.Transparent, tween(160), label = "selectionBorder-$label")
            val textColor by animateColorAsState(if (active) Color.White else Color.White.copy(alpha = .62f), tween(160), label = "selectionText-$label")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(11.dp))
                    .clickable { onSelected(value) }
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickControlsSheet(
    sheetState: SheetState,
    captureFormat: String,
    rawCaptureSupported: Boolean,
    onFormatToggle: () -> Unit,
    onOpenFilterLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val edgePadding = 10.dp
    val sheetShape = quickControlsSheetShape(edgePadding)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.padding(
            start = edgePadding,
            end = edgePadding,
            bottom = edgePadding
        ),
        shape = sheetShape,
        containerColor = PanelBlack,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = .68f),
        tonalElevation = 8.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = QuietWhite.copy(alpha = .42f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, top = 4.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Schnellzugriff", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            SettingGroup("Bildformat") {
                SegmentedSelector(
                    options = if (rawCaptureSupported) {
                        listOf("JPG" to "JPG", "RAW" to "RAW", "RAW+JPG" to "RAW+JPG")
                    } else {
                        listOf("JPG" to "JPG")
                    },
                    selected = captureFormat,
                    onSelected = { selected ->
                        val formats = listOf("JPG", "RAW", "RAW+JPG")
                        repeat((formats.indexOf(selected) - formats.indexOf(captureFormat) + 3) % 3) {
                            onFormatToggle()
                        }
                    }
                )
                if (!rawCaptureSupported) {
                    Text(
                        "RAW wird von dieser Kamera nicht unterstützt.",
                        color = Color.White.copy(alpha = .43f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            HorizontalDivider(color = DividerColor)
            TextButton(onClick = onOpenFilterLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("Filterbibliothek", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(
                    painter = painterResource(R.drawable.ic_pixel_chevron_right),
                    contentDescription = null
                )
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Weitere Einstellungen", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(
                    painter = painterResource(R.drawable.ic_pixel_chevron_right),
                    contentDescription = null
                )
            }
        }
    }
}

/**
 * Keeps the floating sheet parallel to the physical display edge. RoundedCorner
 * reports pixels, while Compose shapes use dp; moving the sheet inward by the
 * same amount reduces the matching corner radius accordingly.
 */
@Composable
private fun quickControlsSheetShape(edgePadding: Dp): RoundedCornerShape {
    val insets = LocalView.current.rootWindowInsets
    val density = LocalDensity.current

    fun radius(position: Int): Dp? = insets
        ?.getRoundedCorner(position)
        ?.radius
        ?.let { radiusPx -> with(density) { radiusPx.toDp() } }
        ?.minus(edgePadding)
        ?.coerceAtLeast(24.dp)

    return RoundedCornerShape(
        topStart = 32.dp,
        topEnd = 32.dp,
        bottomStart = radius(RoundedCorner.POSITION_BOTTOM_LEFT) ?: 28.dp,
        bottomEnd = radius(RoundedCorner.POSITION_BOTTOM_RIGHT) ?: 28.dp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookEditorSheet(
    preset: Preset,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    onSave: (Preset) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var intensity by remember(preset.id) { mutableFloatStateOf(preset.intensity) }
    var grain by remember(preset.id) { mutableFloatStateOf(preset.grain) }
    var halation by remember(preset.id) { mutableFloatStateOf(preset.halation) }
    val edgePadding = 10.dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.padding(
            start = edgePadding,
            end = edgePadding,
            bottom = edgePadding
        ),
        shape = quickControlsSheetShape(edgePadding),
        containerColor = PanelBlack,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = .68f),
        tonalElevation = 8.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = QuietWhite.copy(alpha = .42f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, top = 4.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Look bearbeiten", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = AccentBlue,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = OutlineBlue,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = Color.White.copy(alpha = .72f),
                        focusedContainerColor = ElevatedSurface,
                        unfocusedContainerColor = ElevatedSurface
                    )
                )
                LookSlider("Intensität", intensity, { intensity = it })
                LookSlider("Grain", grain, { grain = it })
                LookSlider("Halation", halation, { halation = it })
                Text(
                    "Grain und Halation werden unabhängig vom importierten Preset angewendet.",
                    color = Color.White.copy(alpha = .52f),
                    style = MaterialTheme.typography.bodySmall
                )
            HorizontalDivider(color = DividerColor)
            if (onDelete != null) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Löschen", modifier = Modifier.weight(1f), textAlign = TextAlign.Start, color = AccentBlue)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { onSave(preset.copy(name = name.trim(), intensity = intensity, grain = grain, halation = halation)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuietWhite, contentColor = Color.Black)
                ) { Text("Sichern", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBlue),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = .72f))
                ) { Text("Abbrechen", fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun LookSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Medium)
            Text("${(value * 100).roundToInt()}%", color = Color.White.copy(alpha = .64f))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = AccentBlue,
                activeTrackColor = AccentBlue,
                inactiveTrackColor = ElevatedSurface,
                activeTickColor = AppSurface,
                inactiveTickColor = OutlineBlue
            )
        )
    }
}

@Composable
private fun PresetAccordionSection(
    groupKey: String,
    title: String,
    presets: List<Preset>,
    selectedIds: Set<String>,
    previewCache: FilterPreviewCache,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    onToggle: (Preset) -> Unit,
    onEdit: (Preset) -> Unit,
    emptyText: String = "",
    onImport: (() -> Unit)? = null
) {
    var showAll by rememberSaveable(groupKey) { mutableStateOf(false) }
    val selectedCount = presets.count { it.id in selectedIds }
    val atLimit = activeFilterCount(selectedIds) >= MaxActiveFilters
    val visiblePresets = if (showAll) presets else presets.take(6)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = TelemetrySurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBlue)
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClick = onExpandedChange)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (!expanded && selectedCount > 0) "$title — $selectedCount gewählt" else title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!expanded && selectedCount > 0) HdrWhite else Color.White
                    )
                }
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_pixel_chevron_up else R.drawable.ic_pixel_chevron_right),
                    contentDescription = if (expanded) "$title einklappen" else "$title aufklappen",
                    tint = QuietWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = DividerColor)
                if (presets.isEmpty()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            emptyText.ifBlank { "Keine Filter in dieser Gruppe" },
                            color = QuietWhite.copy(alpha = .58f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        onImport?.let { import -> LightroomImportButton(import) }
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visiblePresets.chunked(3).forEach { rowPresets ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowPresets.forEach { preset ->
                                    val selected = preset.id in selectedIds
                                    PresetLibraryCard(
                                        preset = preset,
                                        selected = selected,
                                        enabled = selected || !atLimit,
                                        previewCache = previewCache,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f),
                                        onClick = { onToggle(preset) },
                                        onLongClick = { onEdit(preset) }
                                    )
                                }
                                repeat(3 - rowPresets.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (presets.size > 6 && !showAll) {
                            TextButton(
                                onClick = { showAll = true },
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .heightIn(min = 44.dp)
                            ) {
                                Text("+ weitere Varianten", color = AccentBlue, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (atLimit) {
                            Text(
                                "Auswahl-Limit erreicht: Entferne einen Filter, um weitere Varianten zu wählen.",
                                color = QuietWhite.copy(alpha = .72f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        onImport?.let { import -> LightroomImportButton(import) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightroomImportButton(onImport: () -> Unit) {
    OutlinedButton(
        onClick = onImport,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = .78f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HdrWhite)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Lightroom-Presets importieren", fontWeight = FontWeight.SemiBold)
            Text(
                "XMP auswählen, importieren und konvertieren",
                color = QuietWhite.copy(alpha = .7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetLibraryCard(
    preset: Preset,
    selected: Boolean,
    enabled: Boolean,
    previewCache: FilterPreviewCache,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val preview by rememberVisiblePreview(preset, previewCache)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) .94f else 1f,
        animationSpec = spring(dampingRatio = .62f, stiffness = 520f),
        label = "presetPress"
    )
    val selectedTint by animateFloatAsState(
        targetValue = if (selected) .22f else 0f,
        animationSpec = tween(220),
        label = "presetTint"
    )
    val shape = RoundedCornerShape(14.dp)
    val activeAccent = AccentBlue
    val accessibilityState = when {
        selected -> "Gewählt"
        enabled -> "Nicht gewählt"
        else -> "Nicht verfügbar: Auswahl-Limit erreicht"
    }
    Card(
        modifier = modifier
            .shadow(3.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .alpha(if (enabled) 1f else .42f)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                stateDescription = accessibilityState
                contentDescription = "${preset.name}, $accessibilityState"
                if (!enabled) disabled()
            }
            .then(
                if (enabled) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else Modifier
            ),
        shape = shape,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.5.dp, activeAccent)
        } else null,
        colors = CardDefaults.cardColors(containerColor = RaisedBlack)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.bitmap.asImageBitmap(),
                    contentDescription = null,
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
                    .padding(horizontal = 7.dp, vertical = 7.dp)
            ) {
                Text(
                    preset.name,
                    color = if (selected) HdrWhite else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp),
                    shape = CircleShape,
                    color = AccentBlue,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .82f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraAnalysisControlBar(
    analysis: () -> PreviewAnalysis,
    analysisEnabled: Boolean,
    shutterControlVisible: Boolean,
    isoControlVisible: Boolean,
    evControlVisible: Boolean,
    controlRotation: Float,
    shutterOptions: List<Long>,
    selectedShutter: Long,
    shutterManual: Boolean,
    manualExposureSupported: Boolean,
    onShutterSelected: (Long) -> Unit,
    onShutterReset: () -> Unit,
    isoOptions: List<Int>,
    selectedIso: Int,
    isoManual: Boolean,
    manualIsoSupported: Boolean,
    onIsoSelected: (Int) -> Unit,
    onIsoReset: () -> Unit,
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
        if (shutterControlVisible) {
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
                controlRotation = controlRotation,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        if (isoControlVisible) {
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
                controlRotation = controlRotation,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        if (analysisEnabled) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                CombinedExposureAnalysisView(
                    analysis = analysis,
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                )
            }
        }
        if (evControlVisible) {
            VerticalCameraWheel(
                label = "EV",
                values = evOptions,
                selectedValue = selectedEv,
                formatter = { value -> if (abs(value) < .01f) "±0" else String.format(Locale.US, "%+.1f", value) },
                distance = { a, b -> abs(a - b).toDouble() },
                manual = evManual,
                onSelected = onEvSelected,
                onReset = onEvReset,
                controlRotation = controlRotation,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun CombinedExposureAnalysisView(
    analysis: () -> PreviewAnalysis,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .cameraValueMotion(strength = .18f) { analysis() }
            .clip(RoundedCornerShape(23.dp))
            .background(ElevatedSurface)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val currentAnalysis = analysis()
        val histogram = currentAnalysis.luminanceHistogram
        if (histogram.isNotEmpty()) {
            val barWidth = size.width / histogram.size
            histogram.forEachIndexed { index, value ->
                val height = value.coerceIn(0f, 1f) * size.height
                drawLine(
                    color = Color.White.copy(alpha = .28f),
                    start = Offset(index * barWidth, size.height),
                    end = Offset(index * barWidth, size.height - height),
                    strokeWidth = max(1f, barWidth)
                )
            }
        }

        drawLine(
            color = Color.White.copy(alpha = .08f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx()
        )

        fun drawChannel(values: FloatArray, color: Color) {
            if (values.size < 2) return
            for (index in 1 until values.size) {
                val x0 = (index - 1f) / (values.size - 1f) * size.width
                val x1 = index.toFloat() / (values.size - 1f) * size.width
                drawLine(
                    color = color.copy(alpha = .9f),
                    start = Offset(x0, size.height * (1f - values[index - 1].coerceIn(0f, 1f))),
                    end = Offset(x1, size.height * (1f - values[index].coerceIn(0f, 1f))),
                    strokeWidth = 1.2.dp.toPx()
                )
            }
        }
        drawChannel(currentAnalysis.redWaveform, Color(0xFFFF453A))
        drawChannel(currentAnalysis.greenWaveform, Color(0xFF32D74B))
        drawChannel(currentAnalysis.blueWaveform, Color(0xFF64D2FF))
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
    controlRotation: Float,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return
    val view = LocalView.current
    val selectedIndex = values.indices.minByOrNull { distance(values[it], selectedValue) } ?: 0
    val latestIndex by rememberUpdatedState(selectedIndex)
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
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
    val backgroundColor by animateColorAsState(
        targetValue = if (dragging) lerp(ElevatedSurface, AccentBlue, .12f) else ElevatedSurface,
        animationSpec = tween(140),
        label = "wheelBackground-$label"
    )
    val interactionScale by animateFloatAsState(
        targetValue = if (dragging) 1.025f else 1f,
        animationSpec = spring(dampingRatio = .58f, stiffness = 520f),
        label = "wheelInteraction-$label"
    )
    val normalizedRotation = ((controlRotation % 360f) + 360f) % 360f
    val landscape = normalizedRotation in 45f..135f || normalizedRotation in 225f..315f

    Box(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .cameraValueMotion(strength = .55f) { formatter(selectedValue) }
            .graphicsLayer {
                scaleX = interactionScale
                scaleY = interactionScale
            }
            .clip(RoundedCornerShape(28.dp))
            .background(backgroundColor)
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                enabled = enabled,
                onDragStarted = {
                    accumulatedDrag = 0f
                    dragging = true
                },
                onDragStopped = {
                    accumulatedDrag = 0f
                    dragging = false
                }
            )
            .clickable(enabled = manual) { onReset() }
            .padding(horizontal = 3.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = selectedIndex,
            transitionSpec = {
                val advancing = targetState > initialState
                val enteringDirection = if (advancing) 1 else -1
                val slideIn = if (landscape) {
                    // The portrait-locked layout's X axis points physically up/down
                    // in landscape. Reverse it for the opposite landscape side.
                    val physicalDownSign = if (normalizedRotation > 180f) 1 else -1
                    slideInHorizontally(
                        initialOffsetX = { width -> enteringDirection * physicalDownSign * width / 3 },
                        animationSpec = spring(dampingRatio = .72f, stiffness = 470f)
                    )
                } else {
                    slideInVertically(
                        initialOffsetY = { height -> enteringDirection * height / 3 },
                        animationSpec = spring(dampingRatio = .72f, stiffness = 470f)
                    )
                }
                val slideOut = if (landscape) {
                    val physicalDownSign = if (normalizedRotation > 180f) 1 else -1
                    slideOutHorizontally(
                        targetOffsetX = { width -> -enteringDirection * physicalDownSign * width / 3 },
                        animationSpec = spring(dampingRatio = .78f, stiffness = 520f)
                    )
                } else {
                    slideOutVertically(
                        targetOffsetY = { height -> -enteringDirection * height / 3 },
                        animationSpec = spring(dampingRatio = .78f, stiffness = 520f)
                    )
                }
                (slideIn + fadeIn(tween(90)) + scaleIn(
                    initialScale = .94f,
                    animationSpec = spring(dampingRatio = .68f, stiffness = 500f)
                )).togetherWith(slideOut + fadeOut(tween(90)))
            },
            contentAlignment = Alignment.Center,
            label = "telemetryValue-$label",
            modifier = Modifier.fillMaxSize()
        ) { animatedIndex ->
            val safeIndex = animatedIndex.coerceIn(values.indices)
            // Automatic values need not match a selectable manual stop exactly.
            // Render their live telemetry value in the centre instead of a stale nearest stop.
            val centreValue = if (manual) values[safeIndex] else selectedValue
            // Rotate the complete stack so its spatial top-to-bottom order follows
            // the held device. A square landscape layer prevents the rotated ends
            // from being clipped by the telemetry card.
            Column(
                modifier = (if (landscape) {
                    Modifier.fillMaxHeight().aspectRatio(1f)
                } else {
                    Modifier.fillMaxSize()
                }).graphicsLayer { rotationZ = controlRotation }
            ) {
                TelemetryWheelValue(
                    text = safeIndex.takeIf { it > 0 }?.let { formatter(values[it - 1]) } ?: " ",
                    color = Color.White.copy(alpha = .18f),
                    fontSize = 8.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (manual && enabled) AccentBlue else CardSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatter(centreValue),
                        color = when {
                            !enabled -> Color.White.copy(alpha = .34f)
                            manual -> Color.White
                            else -> Color.White
                        },
                        fontFamily = DepartureMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
                TelemetryWheelValue(
                    text = safeIndex.takeIf { it < values.lastIndex }?.let { formatter(values[it + 1]) } ?: " ",
                    color = Color.White.copy(alpha = .18f),
                    fontSize = 8.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (label.isNotBlank()) {
            Text(
                text = label,
                color = Color.White.copy(alpha = if (enabled) .38f else .2f),
                fontFamily = DepartureMono,
                fontWeight = FontWeight.Bold,
                fontSize = 6.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .graphicsLayer { rotationZ = controlRotation }
            )
        }
    }
}

@Composable
private fun TelemetryWheelValue(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = color,
            fontFamily = DepartureMono,
            fontSize = fontSize,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CameraGuidesOverlay(
    selectedAspectRatio: Float,
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    deviceRoll: () -> Float
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
            // The guide must counter-rotate against the phone so it remains parallel
            // to the real horizon instead of following the device tilt.
            val roll = deviceRoll()
            val radians = Math.toRadians(-roll.toDouble())
            val halfLength = 46.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val dx = (kotlin.math.cos(radians) * halfLength).toFloat()
            val dy = (kotlin.math.sin(radians) * halfLength).toFloat()
            val leveled = abs(roll) < 1.2f
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
private fun CaptureQuickControlRow(
    captureMode: CaptureMode,
    flashMode: CameraEngine.FlashMode,
    timerSeconds: Int,
    controlRotation: Float,
    modeSwitchEnabled: Boolean,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onSwitchCamera: () -> Unit,
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CaptureModePill(
            captureMode = captureMode,
            enabled = modeSwitchEnabled,
            controlRotation = controlRotation,
            onModeChange = onCaptureModeChange
        )
        CaptureQuickButton(
            active = false,
            contentDescription = "Kamera wechseln",
            controlRotation = controlRotation,
            onClick = onSwitchCamera
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pixel_switch),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
        CaptureQuickButton(
            active = flashMode != CameraEngine.FlashMode.OFF,
            contentDescription = "Blitz: ${flashMode.name}",
            controlRotation = controlRotation,
            onClick = onFlashClick
        ) {
            Icon(
                painter = painterResource(
                    when (flashMode) {
                        CameraEngine.FlashMode.OFF -> R.drawable.ic_pixel_zap_off
                        CameraEngine.FlashMode.AUTO -> R.drawable.ic_pixel_zap_auto
                        CameraEngine.FlashMode.ON -> R.drawable.ic_pixel_zap
                    }
                ),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
        CaptureQuickButton(
            active = timerSeconds > 0,
            contentDescription = "Selbstauslöser: ${if (timerSeconds == 0) "Aus" else "$timerSeconds Sekunden"}",
            controlRotation = controlRotation,
            onClick = onTimerClick
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pixel_clock),
                contentDescription = null,
                modifier = Modifier.size(19.dp)
            )
            if (timerSeconds > 0) {
                Text(timerSeconds.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CaptureModePill(
    captureMode: CaptureMode,
    enabled: Boolean,
    controlRotation: Float,
    onModeChange: (CaptureMode) -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier
            .width(88.dp)
            .height(36.dp)
            .cameraValueMotion { captureMode }
            .alpha(if (enabled) 1f else .62f),
        shape = shape,
        color = ElevatedSurface
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CaptureMode.values().forEach { mode ->
                val active = captureMode == mode
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val background by animateColorAsState(
                    targetValue = if (active) AccentBlue else CardSurface,
                    animationSpec = spring(
                        dampingRatio = ExpressiveSpringDamping,
                        stiffness = ExpressiveSpringStiffness
                    ),
                    label = "captureModeBackground-${mode.name}"
                )
                val foreground by animateColorAsState(
                    targetValue = if (active) Color.White else Color.White.copy(alpha = .72f),
                    animationSpec = tween(140),
                    label = "captureModeForeground-${mode.name}"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .cameraValueMotion { pressed }
                        .clip(RoundedCornerShape(if (active) 10.dp else 15.dp))
                        .background(background)
                        .clickable(
                            enabled = enabled,
                            interactionSource = interactionSource,
                            indication = LocalIndication.current
                        ) { onModeChange(mode) }
                        .semantics {
                            contentDescription = if (mode == CaptureMode.PHOTO) "Fotomodus" else "Videomodus"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (mode == CaptureMode.PHOTO) R.drawable.ic_pixel_camera
                            else R.drawable.ic_pixel_video
                        ),
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = controlRotation }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureQuickButton(
    active: Boolean,
    contentDescription: String,
    wide: Boolean = false,
    controlRotation: Float,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var iconFlash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val buttonWidth by animateDpAsState(
        targetValue = if (wide) 70.dp else 44.dp,
        animationSpec = spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness
        ),
        label = "quickButtonWidth"
    )
    val containerColor by animateColorAsState(
        targetValue = if (active) AccentBlue else ElevatedSurface,
        animationSpec = spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness
        ),
        label = "quickButtonColor"
    )
    val foregroundColor by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = .76f),
        animationSpec = spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness
        ),
        label = "quickButtonForeground"
    )
    val pressScaleX by animateFloatAsState(
        targetValue = if (pressed) .94f else 1f,
        animationSpec = spring(dampingRatio = .58f, stiffness = 620f),
        label = "quickButtonPressX"
    )
    val pressScaleY by animateFloatAsState(
        targetValue = if (pressed) .88f else 1f,
        animationSpec = spring(dampingRatio = .62f, stiffness = 700f),
        label = "quickButtonPressY"
    )
    val bloomAlpha by animateFloatAsState(
        targetValue = if (pressed || iconFlash) 1f else 0f,
        animationSpec = tween(durationMillis = if (pressed || iconFlash) 65 else 260),
        label = "quickButtonBloom"
    )
    val bloomElevation by animateDpAsState(
        targetValue = if (pressed || iconFlash) 11.dp else 0.dp,
        animationSpec = spring(dampingRatio = .72f, stiffness = 520f),
        label = "quickButtonBloomElevation"
    )
    val handleClick = {
        iconFlash = true
        scope.launch {
            delay(170L)
            iconFlash = false
        }
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onClick()
    }
    Surface(
        modifier = Modifier
            .width(buttonWidth)
            .height(36.dp)
            .cameraValueMotion { contentDescription }
            .semantics { this.contentDescription = contentDescription }
            .graphicsLayer {
                scaleX = pressScaleX
                scaleY = pressScaleY
            }
            .shadow(
                elevation = bloomElevation,
                shape = shape,
                clip = false,
                ambientColor = AccentBlue.copy(alpha = .72f * bloomAlpha),
                spotColor = AccentBlue.copy(alpha = .9f * bloomAlpha)
            )
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = handleClick
            ),
        shape = shape,
        color = containerColor,
        contentColor = foregroundColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            // A soft duplicate behind the crisp glyph makes the icon flare briefly
            // without washing out the pill's border or its compact silhouette.
            if (bloomAlpha > .01f) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                            .graphicsLayer {
                                rotationZ = controlRotation
                                alpha = .72f * bloomAlpha
                                scaleX = 1.08f
                                scaleY = 1.08f
                                renderEffect = BlurEffect(5f, 5f, TileMode.Decal)
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        content = content
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        rotationZ = controlRotation
                        alpha = .76f + .24f * bloomAlpha
                        scaleX = 1f + .035f * bloomAlpha
                        scaleY = 1f + .035f * bloomAlpha
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

@Composable
private fun IdleTelemetryShutterRow(
    shutterPressed: Boolean,
    accentColor: Color,
    captureMode: CaptureMode,
    videoRecordingState: VideoRecordingState,
    recordingSeconds: Long,
    controlRotation: Float,
    onQuickControlsClick: () -> Unit,
    galleryThumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    galleryArrivalScale: Float,
    galleryPendingCount: Int,
    showGalleryArrival: Boolean,
    onGalleryClick: () -> Unit,
    onShutterClick: () -> Unit,
    onShutterSwipe: () -> Unit
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (shutterPressed) .96f else 1f,
        animationSpec = spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness
        ),
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
            .height(76.dp)
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Quick controls keep the camera bar focused while exposing the most
        // frequently changed capture options in a single, reachable sheet.
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (videoRecordingState == VideoRecordingState.RECORDING) {
                Text(
                    text = "%02d:%02d".format(recordingSeconds / 60L, recordingSeconds % 60L),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_pixel_chevron_up),
                    contentDescription = "Schnellzugriff öffnen",
                    tint = Color.White,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .springClickable(feedback = HapticFeedbackConstants.CONTEXT_CLICK, onClick = onQuickControlsClick)
                        .padding(6.dp)
                        .graphicsLayer { rotationZ = controlRotation }
                )
            }
        }

        // Center: a fine white outer ring, dark separator and broad inner disc.
        Box(
            modifier = Modifier
                .scale(shutterScale)
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White)
                .draggable(
                    state = shutterSwipeState,
                    orientation = Orientation.Horizontal
                )
                .clickable { onShutterClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF111216)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            if (videoRecordingState == VideoRecordingState.RECORDING) 30.dp else 58.dp
                        )
                        .clip(
                            if (videoRecordingState == VideoRecordingState.RECORDING) {
                                RoundedCornerShape(7.dp)
                            } else {
                                CircleShape
                            }
                        )
                        .background(
                            if (captureMode == CaptureMode.VIDEO) Color(0xFFFF3B30) else Color.White
                        )
                )
            }
        }

        // Gallery balances the quick-controls affordance and keeps the shutter centred.
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .graphicsLayer {
                        scaleX = galleryArrivalScale
                        scaleY = galleryArrivalScale
                        rotationZ = controlRotation
                    }
                    .clip(RoundedCornerShape(11.dp))
                    .springClickable(feedback = HapticFeedbackConstants.CONTEXT_CLICK, onClick = onGalleryClick),
                contentAlignment = Alignment.Center
            ) {
                galleryThumbnail?.let { thumbnail ->
                    Image(
                        bitmap = thumbnail,
                        contentDescription = "Zuletzt aufgenommenes Foto",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } ?: Icon(
                    painter = painterResource(R.drawable.ic_pixel_images),
                    contentDescription = "Galerie öffnen",
                    tint = Color.White.copy(alpha = .76f),
                    modifier = Modifier.size(22.dp)
                )
                if (galleryPendingCount > 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.matchParentSize().padding(3.dp),
                        color = accentColor,
                        trackColor = Color.White.copy(alpha = .12f),
                        strokeWidth = 1.5.dp
                    )
                    if (galleryPendingCount > 1) {
                        Text(
                            text = galleryPendingCount.toString(),
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
    }
}

// -----------------------------------------------------------------------------------------
// FILTER BOOK (press, hold and leaf through the overlapping Polaroid pages)
// -----------------------------------------------------------------------------------------
@Composable
fun FilterCarouselRow(
    presets: List<Preset>,
    selectedIndex: Int,
    previewCache: FilterPreviewCache,
    shutterPressed: Boolean,
    onSelectPreset: (Int) -> Unit
) {
    if (presets.isEmpty()) return
    // One shared position drives every visible page. Reading this Animatable from
    // graphicsLayer invalidates only drawing/layer properties instead of recomposing
    // and remeasuring up to eleven independently animated cards on every frame.
    val selectionPosition = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        selectionPosition.animateTo(
            targetValue = selectedIndex.toFloat(),
            animationSpec = spring(dampingRatio = .72f, stiffness = 620f)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp),
        contentAlignment = Alignment.Center
    ) {
        presets.forEachIndexed { index, preset ->
            val relativeIndex = index - selectedIndex
            val distance = abs(relativeIndex)
            val direction = when {
                relativeIndex < 0 -> -1f
                relativeIndex > 0 -> 1f
                else -> 0f
            }
            val targetPageLift = when (distance) {
                0 -> 0f
                1 -> -direction * 22f
                2 -> -direction * 9f
                else -> 0f
            }

            // The quick picker contains only the deliberately limited preset set.
            // Keeping every page composed prevents a fast multi-index swipe from
            // removing a still-visible outgoing page or inserting one mid-flight.
            key(preset.id) {
                PolaroidFilterCard(
                    preset = preset,
                    selected = distance == 0,
                    previewCache = previewCache,
                    lightAngle = targetPageLift,
                    depth = distance,
                    onClick = { onSelectPreset(index) },
                    modifier = Modifier
                        .zIndex(20f - distance)
                        .graphicsLayer {
                        val relative = index - selectionPosition.value
                        val animatedDistance = abs(relative)
                        val animatedDirection = when {
                            relative < 0f -> -1f
                            relative > 0f -> 1f
                            else -> 0f
                        }
                        val curvedY = when {
                            animatedDistance <= 1f -> animatedDistance * 7f
                            animatedDistance <= 2f -> 7f + (animatedDistance - 1f) * 9f
                            animatedDistance <= 3f -> 16f + (animatedDistance - 2f) * 9f
                            else -> 25f + (animatedDistance - 3f).coerceAtMost(1f) * 6f
                        }
                        val animatedScale = when {
                            animatedDistance <= 1f ->
                                1.11f + (.96f - 1.11f) * animatedDistance
                            animatedDistance <= 2f ->
                                .96f + (.9f - .96f) * (animatedDistance - 1f)
                            animatedDistance <= 3f ->
                                .9f + (.84f - .9f) * (animatedDistance - 2f)
                            else -> .84f
                        } * if (shutterPressed && animatedDistance < .5f) 1.045f else 1f
                        val animatedAlpha = when {
                            animatedDistance <= 3f -> 1f
                            animatedDistance <= 4f -> 1f - .18f * (animatedDistance - 3f)
                            animatedDistance <= 5f -> .82f - .27f * (animatedDistance - 4f)
                            animatedDistance <= 6f -> .55f * (6f - animatedDistance)
                            else -> 0f
                        }
                        // Each page layer has its own fixed blur strength. Switching
                        // at the midpoint between cards keeps the depth bands distinct
                        // instead of producing a uniformly increasing blur gradient.
                        // An exponential curve keeps the center subtle, becomes much
                        // stronger near the edges and is capped at 3.6 dp.
                        val blurDepth = animatedDistance.roundToInt().coerceIn(0, 5)
                        val blurRadiusDp = if (blurDepth == 0) {
                            0f
                        } else {
                            val curve = (exp(blurDepth * .45f) - 1f) / (exp(5f * .45f) - 1f)
                            3.6f * curve
                        }
                        val blurRadius = blurRadiusDp.dp.toPx()
                        // Continuous across both boundaries. The previous curve
                        // jumped from -10.5° to about +4°, which looked like a card
                        // skipped an animation state during quick swipes.
                        val animatedRotation = when {
                            animatedDistance < .02f -> 0f
                            animatedDistance <= 1f -> -animatedDirection * 10.5f * animatedDistance
                            animatedDistance <= 2f -> {
                                val progress = animatedDistance - 1f
                                animatedDirection * (-10.5f + 15.3f * progress)
                            }
                            else -> animatedDirection * (3.5f + animatedDistance.coerceAtMost(5f) * .65f)
                        }
                        val animatedLift = when {
                            animatedDistance <= 1f -> -animatedDirection * 22f * animatedDistance
                            animatedDistance <= 2f ->
                                -animatedDirection * (22f - 13f * (animatedDistance - 1f))
                            animatedDistance <= 3f ->
                                -animatedDirection * 9f * (3f - animatedDistance)
                            else -> 0f
                        }

                        translationX = relative * 37.dp.toPx()
                        translationY = curvedY.dp.toPx()
                        scaleX = animatedScale
                        scaleY = animatedScale
                        rotationZ = animatedRotation
                        rotationY = animatedLift
                        rotationX = if (animatedDistance < .5f) -1.8f else animatedDirection * 1.4f
                        cameraDistance = 18f * density
                        alpha = animatedAlpha.coerceIn(0f, 1f)
                        renderEffect = if (blurRadius > .01f) {
                            BlurEffect(blurRadius, blurRadius, TileMode.Decal)
                        } else {
                            null
                        }
                        }
                )
            }
        }
    }
}

@Composable
private fun PolaroidFilterCard(
    preset: Preset,
    selected: Boolean,
    previewCache: FilterPreviewCache,
    lightAngle: Float,
    depth: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preview by rememberVisiblePreview(preset, previewCache)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .94f else 1f,
        animationSpec = spring(dampingRatio = .55f, stiffness = 650f),
        label = "polaroidPress-${preset.id}"
    )
    val selectedEmphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = .78f, stiffness = 520f),
        label = "polaroidSelection-${preset.id}"
    )
    val shape = RoundedCornerShape(3.dp)
    val restingElevation = (15 - depth * 2).coerceAtLeast(6).dp
    // A small specular pass sells the paper/card surface. It is just a gradient
    // layer (no bitmap work or blur), and only becomes visible while a card tilts.
    val reflectionAlpha by animateFloatAsState(
        targetValue = ((abs(lightAngle) / 24f) * .26f + if (selected) .055f else 0f)
            .coerceIn(0f, .30f),
        animationSpec = tween(90),
        label = "polaroidReflection-${preset.id}"
    )
    val reflectionRamp = listOf(
        Color.Transparent,
        Color.White.copy(alpha = reflectionAlpha),
        Color.White.copy(alpha = reflectionAlpha * .18f),
        Color.Transparent
    )
    val reflectionColors = if (lightAngle >= 0f) reflectionRamp else reflectionRamp.reversed()

    Card(
        modifier = modifier
            .width(62.dp)
            .height(78.dp)
            .shadow(restingElevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(
            width = (.5f + selectedEmphasis).dp,
            color = lerp(Color(0xFFE7E3DA), AccentBlue, selectedEmphasis)
        ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F6F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF242424)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    preset.isAddButton -> Text(
                        "+",
                        color = Color.White,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light
                    )
                    preview != null -> Image(
                        bitmap = requireNotNull(preview).bitmap.asImageBitmap(),
                        contentDescription = "Vorschau für ${preset.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.5.dp,
                        color = Color.White.copy(alpha = .72f)
                    )
                }
                if (!preset.isAddButton) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = selectedEmphasis }
                            .border(1.dp, AccentBlue.copy(alpha = .9f))
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Brush.linearGradient(reflectionColors))
                    )
                }
            }

            Text(
                text = if (preset.isAddButton) "FILTER" else preset.name.uppercase(Locale.getDefault()),
                color = Color(0xFF151515),
                // Weight changes cannot interpolate and looked like a skipped
                // frame. The animated border/light now carries selection state.
                fontWeight = FontWeight.Bold,
                fontSize = 7.sp,
                letterSpacing = (-0.25f).sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
            )
        }
    }
}

// Matte expressive lens selector. Horizontal zoom gestures are owned by the parent shelf.
@Composable
private fun ZoomPillDial(
    expanded: Boolean,
    currentZoom: Float,
    focalLengths: List<CameraEngine.FocalLengthOption>,
    minimumZoom: Float,
    maximumZoom: Float,
    equivalentFocalLength: Float?,
    displayUnit: ZoomDisplayUnit,
    endStopDirection: Int,
    onSelectZoom: (Float) -> Unit
) {
    val stops = remember(focalLengths, minimumZoom, maximumZoom) {
        (focalLengths.map { it.zoomRatio } + listOf(1f, 2f, 3f))
            .filter { it.isFinite() && it in minimumZoom..maximumZoom }
            .sorted()
            .fold(mutableListOf<Float>()) { result, ratio ->
                if (result.none { abs(it - ratio) < .05f }) result.add(ratio)
                result
            }.ifEmpty { listOf(minimumZoom) }
    }
    val selectedIndex = stops.indices.minByOrNull {
        abs(kotlin.math.ln(currentZoom / stops[it]))
    } ?: 0
    val expansion by animateFloatAsState(
        if (expanded) 1f else 0f,
        spring(dampingRatio = .85f, stiffness = 1100f),
        label = "zoomExpansion"
    )
    val endStop by animateFloatAsState(
        endStopDirection.toFloat(), spring(dampingRatio = .8f, stiffness = 1200f),
        label = "zoomEndStop"
    )
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 90.dp), contentAlignment = Alignment.TopCenter) {
        val cellWidth = (48 + 8 * expansion).dp
        val naturalWidth = cellWidth * stops.size + 16.dp
        val pillWidth = minOf(naturalWidth, maxWidth - 24.dp)
        LaunchedEffect(selectedIndex, expanded, maxWidth) {
            val targetCell = (if (expanded) 56 else 48).dp
            val targetWidth = minOf(targetCell * stops.size + 16.dp, maxWidth - 24.dp)
            val target = with(density) {
                (targetCell * selectedIndex + targetCell / 2 - targetWidth / 2 + 8.dp).roundToPx()
            }.coerceAtLeast(0)
            scrollState.animateScrollTo(target, animationSpec = tween(140))
        }
        val pillShape = RoundedCornerShape(50)
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(pillWidth)
                .cameraValueMotion(strength = .35f) { currentZoom }
                .height((56 + 8 * expansion).dp)
                .graphicsLayer { translationX = -endStop * 3.dp.toPx() }
                .clip(pillShape)
                .background(ElevatedSurface)
                .horizontalScroll(scrollState, enabled = false)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            stops.forEachIndexed { index, ratio ->
                val selected = index == selectedIndex
                val emphasis by animateFloatAsState(
                    if (selected) 1f else 0f,
                    spring(dampingRatio = .9f, stiffness = 1200f),
                    label = "zoomLens-$ratio"
                )
                // Springs may overshoot. Color interpolation and shape values must
                // stay within their valid domain even while the geometry settles.
                val selectionProgress = emphasis.coerceIn(0f, 1f)
                val lensShape = RoundedCornerShape((50 - 18 * selectionProgress).toInt())
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                var flashCounter by remember { mutableIntStateOf(0) }
                var flashing by remember { mutableStateOf(false) }
                LaunchedEffect(flashCounter) {
                    if (flashCounter > 0) {
                        flashing = true
                        delay(170)
                        flashing = false
                    }
                }
                val bloom by animateFloatAsState(
                    if (pressed || flashing) 1f else 0f,
                    tween(if (pressed || flashing) 65 else 260),
                    label = "zoomButtonBloom-$ratio"
                )
                val label = if (selected) {
                    formatZoomValue(currentZoom, equivalentFocalLength, displayUnit)
                } else if (displayUnit == ZoomDisplayUnit.MILLIMETERS) {
                    val focal = focalLengths.firstOrNull { abs(it.zoomRatio - ratio) < .05f }
                    val millimeters = focal?.millimeters ?: equivalentFocalLength?.let { it / currentZoom * ratio }
                    formatZoomValue(ratio, millimeters, displayUnit)
                } else {
                    String.format(Locale.getDefault(), "%.1f", ratio).removeSuffix(",0").removeSuffix(".0")
                }
                Box(
                    Modifier.width(cellWidth).fillMaxHeight()
                        .semantics { stateDescription = if (selected) label else "" }
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = { flashCounter++; onSelectZoom(ratio) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size((34 + 10 * selectionProgress + 2 * expansion).dp)
                            .cameraValueMotion { Pair(pressed, flashCounter) }
                            .shadow(
                                elevation = (11 * bloom).dp,
                                shape = lensShape,
                                clip = false,
                                ambientColor = AccentBlue.copy(alpha = .72f * bloom),
                                spotColor = AccentBlue.copy(alpha = .9f * bloom)
                            )
                            .clip(lensShape)
                            .background(lerp(CardSurface, AccentBlue, selectionProgress)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Match the quick-control glyph flare; only allocate the
                        // small blurred text layer while the button is glowing.
                        if (bloom > .01f) {
                            Text(label, color = Color.White,
                                fontFamily = DepartureMono, fontSize = if (selected) 12.sp else 11.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                                modifier = Modifier.graphicsLayer {
                                    alpha = .72f * bloom
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                    renderEffect = BlurEffect(5f, 5f, TileMode.Decal)
                                })
                        }
                        Text(label, color = if (selected) Color.White else Color.White.copy(alpha = .72f),
                            fontFamily = DepartureMono, fontSize = if (selected) 12.sp else 11.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Release Compose's bitmap references while stopped and reload on return. */
@Composable
private fun rememberVisiblePreview(
    preset: Preset,
    cache: FilterPreviewCache
): androidx.compose.runtime.State<FilterPreview?> {
    val owner = LocalLifecycleOwner.current
    return produceState<FilterPreview?>(initialValue = null, preset, cache, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                value = if (preset.isAddButton) null else cache.preview(preset)
                awaitCancellation()
            } finally {
                value = null
            }
        }
    }
}
