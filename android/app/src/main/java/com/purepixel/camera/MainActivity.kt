package com.purepixel.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.purepixel.camera.camera.CameraEngine
import com.purepixel.camera.ui.HooruTypography
import com.purepixel.camera.ui.PurePixelScreen
import com.purepixel.camera.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private var cameraEngine: CameraEngine? = null
    private val cameraReady = mutableStateOf(false)
    private val setupCompleted = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) prepareCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        initUi()
        setupCompleted.value = getSharedPreferences("onboarding", MODE_PRIVATE)
            .getBoolean("setup_completed", false)

        // Let Android replace its starting window with our black Compose surface
        // before doing camera discovery or presenting the permission dialog.
        window.decorView.post {
            if (setupCompleted.value) prepareCameraAfterSetup()
        }
    }

    private fun initUi() {
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1C85F3),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF1C85F3),
                    onPrimaryContainer = Color.White,
                    secondary = Color(0xFF243140),
                    onSecondary = Color.White,
                    background = Color.Black,
                    onBackground = Color.White,
                    surface = Color(0xFF05070A),
                    onSurface = Color.White,
                    surfaceVariant = Color(0xFF0A0F15),
                    onSurfaceVariant = Color.White,
                    surfaceDim = Color(0xFF05070A),
                    surfaceBright = Color(0xFF101821),
                    surfaceContainerLowest = Color(0xFF05070A),
                    surfaceContainerLow = Color(0xFF0A0F15),
                    surfaceContainer = Color(0xFF0A0F15),
                    surfaceContainerHigh = Color(0xFF101821),
                    surfaceContainerHighest = Color(0xFF101821),
                    outline = Color(0xFF243140),
                    outlineVariant = Color(0xFF17202A)
                ),
                typography = HooruTypography
            ) {
                if (!setupCompleted.value) {
                    SetupScreen(onFinished = ::completeSetup)
                } else if (cameraReady.value) {
                    PurePixelScreen(
                        cameraEngine = requireNotNull(cameraEngine),
                        onFirstPreviewFrame = ::requestBalancedRefreshRate
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }

    private fun completeSetup() {
        getSharedPreferences("onboarding", MODE_PRIVATE)
            .edit()
            .putBoolean("setup_completed", true)
            .apply()
        setupCompleted.value = true
        prepareCameraAfterSetup()
    }

    private fun prepareCameraAfterSetup() {
        if (hasCameraPermission()) {
            prepareCamera()
        } else {
            requestPermissionsLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun prepareCamera() {
        if (cameraEngine != null) {
            cameraReady.value = true
            return
        }
        // Construction is small but still kept behind the first black frame. The
        // handler must exist before Compose creates the GL camera surface.
        cameraEngine = CameraEngine(this).also { engine ->
            engine.startBackgroundThread()
            engine.startOrientationTracking()
        }
        cameraReady.value = true
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The camera delivers at most 30 frames per second. Keeping a 120/144 Hz panel
     * forced to its fastest mode therefore burns power without improving preview
     * motion. A 60 Hz window still leaves enough headroom for responsive Compose
     * gestures while allowing the display compositor to choose an efficient mode.
     */
    private fun requestBalancedRefreshRate() {
        val display = display ?: return
        val refreshRate = display.supportedModes
            .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
            ?.refreshRate
            ?: display.refreshRate.coerceAtMost(60f)
        window.attributes = window.attributes.apply { preferredRefreshRate = refreshRate }
    }

    override fun onResume() {
        super.onResume()
        cameraEngine?.let { engine ->
            engine.startBackgroundThread()
            engine.startOrientationTracking()
            engine.resumeCamera()
        }
    }

    override fun onPause() {
        cameraEngine?.let { engine ->
            engine.stopOrientationTracking()
            engine.closeCamera()
            engine.stopBackgroundThread()
        }
        super.onPause()
    }

    override fun onDestroy() {
        cameraEngine?.release()
        cameraEngine = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.repeatCount == 0
        ) {
            cameraEngine?.let {
                it.requestHardwareShutter()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
