package dev.retrobit.mirror

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.retrobit.mirror.client.HardwareInput
import dev.retrobit.mirror.client.MirrorClient
import dev.retrobit.mirror.host.HostRuntime
import dev.retrobit.mirror.host.MirrorHostService
import dev.retrobit.mirror.model.QualityPreset
import dev.retrobit.mirror.ui.RetroApp

class MainActivity : ComponentActivity() {
    private val client = MirrorClient()
    private var pendingPreset = QualityPreset.BALANCED
    private var pendingAudio = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingAudio = pendingAudio && granted
        askNotification()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchProjection()
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorHostService.start(this, result.resultCode, data, pendingPreset, pendingAudio)
        } else {
            HostRuntime.update { it.copy(error = "Izin tangkap layar ditolak. Pilih seluruh layar, bukan satu aplikasi, kalau game-nya belum terbuka.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RetroApp(
                client = client,
                onStartMirror = { preset, audio -> beginMirror(preset, audio) },
                onStopMirror = { MirrorHostService.stop(this) }
            )
        }
    }

    private fun beginMirror(preset: QualityPreset, audio: Boolean) {
        pendingPreset = preset
        pendingAudio = audio
        if (audio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            askNotification()
        }
    }

    private fun askNotification() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        projection.launch(mgr.createScreenCaptureIntent())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val listener = HardwareInput.onKey
        if (listener != null && shouldForward(event)) {
            val device = InputDevice.getDevice(event.deviceId)
            val gamepad = device != null && (device.sources and InputDevice.SOURCE_GAMEPAD) != 0
            listener.invoke(event.action, event.keyCode, event.metaState, gamepad)
            if (gamepad || HardwareInput.captureKeyboard) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val listener = HardwareInput.onAxes
        if (listener != null && event.action == MotionEvent.ACTION_MOVE &&
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        ) {
            val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_BRAKE)
            val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_GAS)
            listener.invoke(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                lt,
                rt,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun shouldForward(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER) return false
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        return !device.isVirtual
    }
}
