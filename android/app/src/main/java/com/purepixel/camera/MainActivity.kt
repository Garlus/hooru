package com.purepixel.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.purepixel.camera.camera.CameraEngine
import com.purepixel.camera.ui.PurePixelScreen

class MainActivity : ComponentActivity() {

    private var cameraEngine: CameraEngine? = null
    private val cameraReady = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            prepareCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Keep the camera window in the default SDR composition mode. Forcing an
        // HDR window makes separate GLSurfaceView layers render black on a number
        // of otherwise HDR-capable devices. Filter highlights retain their glow.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
        initUi()

        // Let Android replace its starting window with our black Compose surface
        // before doing camera discovery or presenting the permission dialog.
        window.decorView.post {
            if (hasRequiredPermissions()) {
                prepareCamera()
            } else {
                requestPermissionsLauncher.launch(requiredPermissions())
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun initUi() {
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF453A),
                    onPrimary = Color.Black,
                    surface = Color(0xFF101010),
                    surfaceVariant = Color(0xFF252525),
                    background = Color.Black
                )
            ) {
                if (cameraReady.value) {
                    PurePixelScreen(
                        cameraEngine = requireNotNull(cameraEngine),
                        onFirstPreviewFrame = ::requestHighestRefreshRate
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
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

    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val storageGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && storageGranted
    }

    /** Keep Compose interactions, including the settings screen, on the panel's fastest mode. */
    @Suppress("DEPRECATION")
    private fun requestHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        } ?: return
        val refreshRate = if (Build.VERSION.SDK_INT >= 23) {
            display.supportedModes.maxOfOrNull { it.refreshRate }
        } else {
            display.refreshRate
        } ?: return
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
