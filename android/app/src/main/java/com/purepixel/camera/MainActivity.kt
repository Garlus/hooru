package com.purepixel.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.purepixel.camera.camera.CameraEngine
import com.purepixel.camera.ui.PurePixelScreen

class MainActivity : ComponentActivity() {

    private lateinit var cameraEngine: CameraEngine

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            initUi()
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
        cameraEngine = CameraEngine(this)
        // The GL renderer can publish its SurfaceTexture during setContent(), before
        // onResume(). Camera2 therefore needs its handler before the UI is composed.
        cameraEngine.startBackgroundThread()

        if (hasRequiredPermissions()) {
            initUi()
        } else {
            requestPermissionsLauncher.launch(
                arrayOf(Manifest.permission.CAMERA)
            )
        }
    }

    private fun initUi() {
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8AB4F8),
                    onPrimary = Color(0xFF07111F),
                    surface = Color(0xFF101010),
                    surfaceVariant = Color(0xFF252525),
                    background = Color.Black
                )
            ) {
                PurePixelScreen(cameraEngine = cameraEngine)
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        // The GL lifecycle observer may publish a recreated SurfaceTexture from
        // inside super.onResume(), so Camera2's callback thread must exist first.
        cameraEngine.startBackgroundThread()
        super.onResume()
        cameraEngine.resumeCamera()
    }

    override fun onPause() {
        cameraEngine.closeCamera()
        cameraEngine.stopBackgroundThread()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.repeatCount == 0
        ) {
            cameraEngine.requestHardwareShutter()
            return true
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
