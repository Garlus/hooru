package com.purepixel.camera.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
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
import com.purepixel.camera.camera.CameraEngine
import com.purepixel.camera.gl.CameraPreviewGL
import com.purepixel.camera.gl.GLCameraView
import com.purepixel.camera.model.Preset
import com.purepixel.camera.model.LightroomPreset
import com.purepixel.camera.model.FilterPreviewCache
import com.purepixel.camera.model.FilterPreview
import com.purepixel.camera.model.PresetLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

enum class UiStateMode {
    IDLE,          // Image 1: Telemetry on shutter row, minimal 1-tick zoom below
    ZOOM_ACTIVE,   // Image 3: Expanded Gaussian Zoom Rocker dial below shutter
    FILTER_ACTIVE  // Image 2: Swipe on shutter opens Filter Carousel, auto-fades back after 2s
}

private fun buildShutterPresets(custom: List<Preset>, selectedIds: Set<String>): List<Preset> {
    val builtIns = Preset.DEFAULT_PRESETS.filterNot(Preset::isAddButton)
    val selected = (builtIns + custom).filter { it.id == "no_filter" || it.id in selectedIds }
    return selected + Preset.DEFAULT_PRESETS.first(Preset::isAddButton)
}

private fun filterCode(preset: Preset): String {
    val aliases = mapOf(
        "leica_mono" to "LEIC", "teal_orange" to "TEAL", "no_filter" to "NONE",
        "portra_400" to "PORT", "classic_chrome" to "FUJI", "warm_fade" to "WARM",
        "cool_night" to "COOL", "high_contrast" to "HIGH", "cinema_green" to "CINE",
        "soft_rose" to "ROSE"
    )
    return aliases[preset.id] ?: preset.name.uppercase().filter(Char::isLetterOrDigit).padEnd(4, '·').take(4)
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
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var telemetry by remember { mutableStateOf(CameraEngine.TelemetryData()) }
    var glView by remember { mutableStateOf<GLCameraView?>(null) }
    var backgroundFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isoBoostEnabled by remember { mutableStateOf(false) }
    var shutterBoostEnabled by remember { mutableStateOf(false) }
    var previewBlackout by remember { mutableStateOf(false) }
    var blackoutDurationMs by remember { mutableLongStateOf(80L) }
    var shutterSequence by remember { mutableIntStateOf(0) }
    var shutterPressed by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusSequence by remember { mutableIntStateOf(0) }

    var customPresets by remember { mutableStateOf(presetLibrary.customPresets()) }
    val defaultSelectedIds = remember {
        Preset.DEFAULT_PRESETS.filterNot(Preset::isAddButton).map(Preset::id).toSet()
    }
    var selectedPresetIds by remember {
        mutableStateOf(presetLibrary.selectedIds(defaultSelectedIds))
    }
    var presets by remember {
        mutableStateOf(buildShutterPresets(customPresets, selectedPresetIds))
    }
    var activePresetIndex by remember {
        mutableIntStateOf(presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0))
    }
    var pendingPresetIndex by remember { mutableIntStateOf(activePresetIndex) }
    var uiMode by remember { mutableStateOf(UiStateMode.IDLE) }
    var showPresetSheet by remember { mutableStateOf(false) }
    var isImportingPreset by remember { mutableStateOf(false) }
    var recentRevision by remember { mutableIntStateOf(0) }
    var gallerySaveState by remember { mutableStateOf(CameraEngine.GallerySaveState()) }
    val galleryArrivalScale = remember { Animatable(1f) }
    var showGalleryArrival by remember { mutableStateOf(false) }
    
    // Zoom ratio (0.5x to 8.0x)
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

    // Native XMP import. Parsing, LUT generation and card rendering stay off the UI thread.
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImportingPreset = true
            val progressSnackbar = launch {
                snackbarHostState.showSnackbar(
                    message = "Lightroom-Filter wird importiert und konvertiert …",
                    duration = SnackbarDuration.Indefinite
                )
            }
            val importResult = runCatching {
                val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Lightroom"
                val imported = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Die Datei konnte nicht gelesen werden.")
                    presetLibrary.importXmp(text, displayName.substringBeforeLast('.'))
                }
                // Finish conversion before announcing success, so the card appears immediately.
                previewCache.preview(imported)
                val updatedCustom = presetLibrary.customPresets()
                val nextIds = selectedPresetIds + imported.id
                customPresets = updatedCustom
                selectedPresetIds = nextIds
                presetLibrary.saveSelected(nextIds)
                presets = buildShutterPresets(updatedCustom, nextIds)
                recentRevision++
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            progressSnackbar.cancel()
            isImportingPreset = false
            importResult.fold(
                onSuccess = { snackbarHostState.showSnackbar("Filter importiert und zur Kamera-Leiste hinzugefügt.") },
                onFailure = { error -> snackbarHostState.showSnackbar(error.message ?: "Preset konnte nicht importiert werden.") }
            )
        }
    }

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

    LaunchedEffect(gallerySaveState.failedRevision) {
        if (gallerySaveState.failedRevision > 0) {
            snackbarHostState.showSnackbar("Das Foto konnte nicht in der Galerie gespeichert werden.")
        }
    }

    LaunchedEffect(smoothedZoom) {
        cameraEngine.setZoomRatio(smoothedZoom)
    }

    // Listen to real-time camera telemetry
    DisposableEffect(Unit) {
        cameraEngine.onTelemetryListener = { data ->
            telemetry = data
        }
        cameraEngine.onGallerySaveStateListener = { state ->
            gallerySaveState = state
        }
        onDispose {
            cameraEngine.onTelemetryListener = null
            cameraEngine.onPreviewConfigurationListener = null
            cameraEngine.onGallerySaveStateListener = null
        }
    }

    val activePreset = presets.getOrElse(activePresetIndex) { presets[0] }
    val accentColor = Color(activePreset.accentColor)
    val latestPresets by rememberUpdatedState(presets)
    val latestActivePresetIndex by rememberUpdatedState(activePresetIndex)
    val latestGlView by rememberUpdatedState(glView)
    val latestCurrentZoom by rememberUpdatedState(currentZoom)
    val zoomMilestones = remember { listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f) }

    LaunchedEffect(activePreset.id, activePreset.lightroom, glView) {
        applyPresetToPreview(glView, activePreset)
        cameraEngine.setCaptureFilter(activePreset.id, activePreset.lightroom)
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
            // 1. FIXED LIVE PREVIEW (Centered 4:3 with Outer Padding & Rounded Corners - NEVER MOVES)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 10.dp, end = 10.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { position ->
                                if (size.width > 0 && size.height > 0) {
                                    isoBoostEnabled = false
                                    shutterBoostEnabled = false
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
                        cameraEngine.setCaptureFilter(activePreset.id, activePreset.lightroom)
                    },
                    onPreviewFrame = { bitmap ->
                        backgroundFrame = bitmap.asImageBitmap()
                    }
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
                                shutterPressed = false
                                blackoutDurationMs = cameraEngine.triggerCapture()
                                shutterSequence++
                                hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                                    cameraEngine.setCaptureFilter(selected.id, selected.lightroom)
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
                                cameraEngine.setCaptureFilter(selected.id, selected.lightroom)
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
                        onFormatToggle = {
                            val nextFormat = when (cameraEngine.captureFormat) {
                                "JPG" -> "RAW"
                                "RAW" -> "RAW+JPG"
                                else -> "JPG"
                            }
                            cameraEngine.captureFormat = nextFormat
                            telemetry = telemetry.copy(format = nextFormat)
                        },
                        isoBoostEnabled = isoBoostEnabled,
                        shutterBoostEnabled = shutterBoostEnabled,
                        onIsoBoostToggle = {
                            isoBoostEnabled = cameraEngine.toggleIsoBoost()
                        },
                        onShutterBoostToggle = {
                            shutterBoostEnabled = cameraEngine.toggleShutterBoost()
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

            Spacer(modifier = Modifier.height(16.dp))

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
                                val nearestMilestone = zoomMilestones.minByOrNull {
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
                                    val isMilestone = zoomMilestones.any { abs(it - stepped) < 0.01f }
                                    val feedback = if (isMilestone) {
                                        HapticFeedbackConstants.CONTEXT_CLICK
                                    } else {
                                        HapticFeedbackConstants.CLOCK_TICK
                                    }
                                    hostView.performHapticFeedback(feedback)
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
                            onZoomChange = { },
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
                        .clip(CircleShape)
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
                    Text(
                        text = "▣",
                        color = if (showGalleryArrival) accentColor else Color.White.copy(alpha = 0.75f),
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
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

            Spacer(modifier = Modifier.height(20.dp))
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
        val allLibraryPresets = Preset.DEFAULT_PRESETS.filterNot(Preset::isAddButton) + customPresets
        val recentPresets = remember(customPresets, recentRevision) {
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
                    presets = buildShutterPresets(customPresets, nextIds)
                    if (activeId == preset.id && wasSelected) {
                        activePresetIndex = presets.indexOfFirst { it.id == "no_filter" }.coerceAtLeast(0)
                        pendingPresetIndex = activePresetIndex
                        glView?.clearPreset()
                        cameraEngine.setCaptureFilter("no_filter", null)
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
                }
            )
        }
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
    onImport: () -> Unit
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
                "Antippen, um die Kamera-Leiste anzupassen",
                modifier = Modifier.padding(top = 3.dp),
                color = Color.White.copy(alpha = .58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(7.dp))
        PresetSection("Trending", trending, selectedIds, previewCache, onToggle)
        PresetSection("Zuletzt benutzt", recent, selectedIds, previewCache, onToggle, emptyText = "Noch keine Filter benutzt")
        PresetSection("Von dir", custom, selectedIds, previewCache, onToggle, emptyText = "Importierte Lightroom-Filter erscheinen hier")
        Button(
            onClick = onImport,
            enabled = !importing,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).heightIn(min = 54.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text(if (importing) "Preset wird importiert …" else "Lightroom-Preset importieren", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PresetSection(
    title: String,
    presets: List<Preset>,
    selectedIds: Set<String>,
    previewCache: FilterPreviewCache,
    onToggle: (Preset) -> Unit,
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
                        onClick = { onToggle(preset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetLibraryCard(
    preset: Preset,
    selected: Boolean,
    previewCache: FilterPreviewCache,
    cardSize: Dp = 108.dp,
    cornerRadius: Dp = 22.dp,
    labelSize: androidx.compose.ui.unit.TextUnit = 21.sp,
    onClick: () -> Unit
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
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick),
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
            Text(
                filterCode(preset),
                color = if (selected) HdrWhite else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = labelSize,
                letterSpacing = if (cardSize < 70.dp) .3.sp else 1.2.sp,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = if (selected && activeAccent != null) {
                        Shadow(color = activeAccent.copy(alpha = .9f), blurRadius = 10f)
                    } else null
                )
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
    onFormatToggle: () -> Unit,
    isoBoostEnabled: Boolean,
    shutterBoostEnabled: Boolean,
    onIsoBoostToggle: () -> Unit,
    onShutterBoostToggle: () -> Unit,
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
        // Left 1: ISO
        Text(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)) {
                    append(if (isoBoostEnabled) "ISO+ " else "ISO ")
                }
                withStyle(style = SpanStyle(color = Color.White, fontWeight = FontWeight.Medium)) {
                    append("${telemetry.iso}")
                }
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clickable { onIsoBoostToggle() }
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
                .clickable { onFormatToggle() }
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

        // Right 1: Shutterspeed
        Text(
            text = if (shutterBoostEnabled) "${telemetry.shutterSpeed}+" else telemetry.shutterSpeed,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clickable { onShutterBoostToggle() }
        )

        // Right 2: Settings Button
        Text(
            text = "Settings",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .springClickable(feedback = HapticFeedbackConstants.CLOCK_TICK) { /* Settings dialog hook */ }
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

        // Current zoom level text (e.g. 1.3)
        Text(
            text = String.format("%.1f", currentZoom),
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
    onZoomChange: (Float) -> Unit,
    motionVelocity: Float = 0f
) {
    // Zoom range with a 0.2x rocker scale. 0.5x remains a dedicated ultra-wide stop.
    val minZoom = 0.5f
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

    val milestones = listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f)
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
        // Ticks and milestone labels share the exact same moving coordinate system.
        Canvas(modifier = Modifier.fillMaxSize()) {
                val centerPx = size.width / 2f
                val spacingPx = 9.dp.toPx()
                val tickAreaHeight = 42.dp.toPx()

                fun zoomToIndex(zoom: Float): Float = if (zoom <= 0.6f) {
                    (zoom - 0.5f) / 0.1f
                } else {
                    1f + (zoom - 0.6f) / 0.2f
                }
                val currentIndex = zoomToIndex(currentZoom)

                for (i in zoomTicks.indices) {
                    val tickZoom = zoomTicks[i]
                    val distFromCenterIndex = i - currentIndex
                    val tickX = centerPx + distFromCenterIndex * spacingPx

                    // Clip ticks outside visible bounds
                    if (tickX < 0 || tickX > size.width) continue

                    // Gaussian Normal Distribution Height Math: h = hBase + (hMax - hBase) * exp(- (d^2) / (2 * sigma^2))
                    val absDist = abs(distFromCenterIndex.toFloat())
                    val sigma = 2.2f
                    val gaussianWeight = exp(- (absDist * absDist) / (2f * sigma * sigma))

                    val baseHeight = 10.dp.toPx()
                    val maxHeight = 34.dp.toPx()
                    val tickHeight = baseHeight + (maxHeight - baseHeight) * gaussianWeight

                    // Edge alpha fade out
                    val distFromEdge = minOf(tickX, size.width - tickX)
                    val edgeFade = (distFromEdge / (size.width * 0.25f)).coerceIn(0f, 1f)

                    val strokeColor = Color.White.copy(
                        alpha = (0.2f + 0.6f * gaussianWeight) * edgeFade
                    )
                    val strokeWidth = 1.25.dp.toPx()

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

                // Fixed indicator: the continuously moving scale can stop between ticks.
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
                milestones.forEach { milestone ->
                    val milestoneIndex = zoomToIndex(milestone)
                    val labelX = centerPx + (milestoneIndex - currentIndex) * spacingPx
                    if (labelX < 0f || labelX > size.width) return@forEach
                    val distFromEdge = minOf(labelX, size.width - labelX)
                    val edgeFade = (distFromEdge / (size.width * 0.2f)).coerceIn(0f, 1f)
                    val isSelected = abs(currentZoom - milestone) < 0.05f
                    labelPaint.color = if (isSelected) 0xFFFF3B30.toInt() else 0xFFFFFFFF.toInt()
                    labelPaint.alpha = if (isSelected) 255 else (128 * edgeFade).roundToInt()
                    labelPaint.typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format("%.1f", milestone),
                        labelX,
                        60.dp.toPx(),
                        labelPaint
                    )
                }
            }
    }
}
