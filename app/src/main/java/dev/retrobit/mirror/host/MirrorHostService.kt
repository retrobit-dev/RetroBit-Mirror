package dev.retrobit.mirror.host

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.retrobit.mirror.MainActivity
import dev.retrobit.mirror.R
import dev.retrobit.mirror.RetroBitApp
import dev.retrobit.mirror.capture.AudioCapture
import dev.retrobit.mirror.capture.JpegPump
import dev.retrobit.mirror.capture.ScreenEncoder
import dev.retrobit.mirror.input.InputHub
import dev.retrobit.mirror.input.ShizukuGate
import dev.retrobit.mirror.model.QualityPreset
import dev.retrobit.mirror.model.ViewMode
import dev.retrobit.mirror.net.DiscoveryBeacon
import dev.retrobit.mirror.net.NativeServer
import dev.retrobit.mirror.net.WebPortal
import dev.retrobit.mirror.util.DeviceHints
import dev.retrobit.mirror.util.DisplayInfo
import dev.retrobit.mirror.util.Locks
import dev.retrobit.mirror.util.NetInfo
import dev.retrobit.mirror.util.Prefs
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MirrorHostService : Service() {
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var audio: AudioCapture? = null
    private var jpeg: JpegPump? = null
    private var nativeServer: NativeServer? = null
    private var web: WebPortal? = null
    private var beacon: DiscoveryBeacon? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var cpuLock: android.os.PowerManager.WakeLock? = null
    private var bubble: TextView? = null
    private var deck: HostDeck? = null
    private val main = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private val frameCount = AtomicInteger(0)
    private val byteCount = AtomicLong(0)
    private var statsThread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { shutdown("Izin tangkap layar dicabut") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantsAudio = intent?.getBooleanExtra(EXTRA_AUDIO, false) == true && hasMicPermission()
        startInForeground(wantsAudio)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(null)
                return START_NOT_STICKY
            }
            ACTION_MODE -> {
                val mode = ViewMode.normalize(intent.getStringExtra(EXTRA_MODE))
                Prefs.setViewMode(this, mode)
                if (HostRuntime.session != null) {
                    restartEncoder()
                    showDeck(mode)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (HostRuntime.session == null) {
                    val code = intent.getIntExtra(EXTRA_CODE, 0)
                    val data = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }
                    if (data == null) {
                        shutdown("Izin tangkap layar tidak lengkap")
                        return START_NOT_STICKY
                    }
                    begin(code, data, QualityPreset.fromId(intent.getStringExtra(EXTRA_PRESET)), wantsAudio)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent, preset: QualityPreset, audioOn: Boolean) {
        try {
            val size = DisplayInfo.realSize(this)
            InputHub.displayW = size.first
            InputHub.displayH = size.second
            val pin = Prefs.pin(this)
            val name = Prefs.hostName(this)
            val session = HostSession(pin, name)
            HostRuntime.session = session
            ShizukuGate.refreshStatus(this)
            if (ShizukuGate.status == ShizukuGate.READY) ShizukuGate.bind(this)
            InputHub.refreshGates()

            val mgr = getSystemService(MediaProjectionManager::class.java)
            val projection = mgr.getMediaProjection(code, data) ?: error("MediaProjection kosong")
            this.projection = projection
            projection.registerCallback(projectionCallback, main)

            wifiLock = Locks.wifi(this).also { it.acquire() }
            cpuLock = Locks.cpu(this).also { it.acquire() }

            val jpegPump = JpegPump(projection, size.first, size.second, DisplayInfo.dpi(this))
            jpeg = jpegPump
            val webPortal = WebPortal(this, session, jpegPump)
            val httpPort = webPortal.start()
            web = webPortal
            val tcp = NativeServer(session)
            val tcpPort = tcp.start()
            nativeServer = tcp
            val beacon = DiscoveryBeacon(this, name, tcpPort, httpPort)
            beacon.start()
            this.beacon = beacon

            startEncoder(session, preset)
            showDeck(Prefs.viewMode(this))

            if (audioOn) {
                val capture = AudioCapture(
                    projection,
                    onConfig = { csd, rate, ch -> session.publishAudioConfig(csd, rate, ch) },
                    onFrame = { bytes, pts -> session.publishAudio(bytes, pts) }
                )
                capture.start()
                audio = capture
            }

            watchDisplay()
            watchStats()
            if (Prefs.bubble(this)) showBubble()

            val ip = NetInfo.ipv4() ?: "Wi-Fi mati"
            HostRuntime.update {
                it.copy(
                    running = true,
                    ip = ip,
                    tcpPort = tcpPort,
                    httpPort = httpPort,
                    pin = pin,
                    preset = preset.id,
                    audio = audioOn,
                    bubble = Prefs.bubble(this),
                    error = null,
                    injection = InputHub.injectionName(),
                    shizuku = ShizukuGate.status,
                    accessibility = dev.retrobit.mirror.input.RetroBitAccessibilityService.instance != null,
                    viewMode = Prefs.viewMode(this),
                    hint = if (DeviceHints.isHuawei()) {
                        "Perangkat ini hanya menampilkan. Semua kontrol ada di GT 30 Pro."
                    } else {
                        "Game tetap di ponsel. MatePad hanya menerima layar, tanpa gamepad."
                    }
                )
            }
            updateNotification()
            Log.i(TAG, "mirror up $ip tcp=$tcpPort http=$httpPort")
        } catch (t: Throwable) {
            Log.e(TAG, "begin", t)
            shutdown(t.message ?: "Gagal memulai pantulan")
        }
    }

    private fun watchStats() {
        statsThread = Thread({
            while (HostRuntime.session != null) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                val frames = frameCount.getAndSet(0)
                val bytes = byteCount.getAndSet(0)
                HostRuntime.update {
                    it.copy(
                        fps = frames,
                        bitrateKbps = (bytes * 8 / 1000).toInt(),
                        injection = InputHub.injectionName(),
                        ip = NetInfo.ipv4() ?: it.ip
                    )
                }
                HostRuntime.session?.publishState()
                updateNotification()
            }
        }, "retrobit-stats").also { it.start() }
    }

    private fun watchDisplay() {
        val dm = getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != 0) return
                main.removeCallbacks(restartCapture)
                main.postDelayed(restartCapture, 280)
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, main)
    }

    private val restartCapture = Runnable {
        if (HostRuntime.session == null) return@Runnable
        val size = DisplayInfo.realSize(this)
        if (size.first == InputHub.displayW && size.second == InputHub.displayH) return@Runnable
        InputHub.displayW = size.first
        InputHub.displayH = size.second
        restartEncoder()
    }

    private fun restartEncoder() {
        val session = HostRuntime.session ?: return
        val projection = projection ?: return
        val preset = QualityPreset.fromId(HostRuntime.state.value.preset)
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        startEncoder(session, preset)
        HostRuntime.update { it.copy(viewMode = Prefs.viewMode(this)) }
        session.publishState()
    }

    private fun startEncoder(session: HostSession, preset: QualityPreset) {
        val projection = projection ?: return
        val mode = Prefs.viewMode(this)
        val next = ScreenEncoder(
            projection = projection,
            displayW = InputHub.displayW,
            displayH = InputHub.displayH,
            dpi = DisplayInfo.dpi(this),
            preset = preset,
            cropFraction = ViewMode.crop(mode),
            onConfig = { w, h, csd ->
                session.publishConfig(w, h, csd)
                HostRuntime.update { it.copy(videoLabel = "${w}×${h}") }
            },
            onFrame = { bytes, key, pts ->
                frameCount.incrementAndGet()
                byteCount.addAndGet(bytes.size.toLong())
                session.publishVideo(bytes, key, pts)
            },
            onError = { message -> HostRuntime.update { it.copy(error = message) } }
        )
        session.requestKeyframe = { next.requestSync() }
        next.start()
        encoder = next
    }

    private fun showDeck(mode: String) {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            HostRuntime.update { it.copy(error = "Izinkan tampil di atas aplikasi lain supaya gamepad muncul di pinggir layar.") }
            return
        }
        val shown = deck ?: HostDeck(this).also { deck = it }
        main.post { shown.show(mode) }
    }

    private fun shutdown(error: String?) {
        statsThread?.interrupt()
        statsThread = null
        displayListener?.let {
            try {
                getSystemService(DisplayManager::class.java).unregisterDisplayListener(it)
            } catch (_: Throwable) {
            }
        }
        displayListener = null
        hideBubble()
        deck?.hide()
        deck = null
        try {
            beacon?.stop()
        } catch (_: Throwable) {
        }
        beacon = null
        try {
            nativeServer?.stop()
        } catch (_: Throwable) {
        }
        nativeServer = null
        try {
            web?.stop()
        } catch (_: Throwable) {
        }
        web = null
        try {
            audio?.stop()
        } catch (_: Throwable) {
        }
        audio = null
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        encoder = null
        jpeg = null
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        try {
            wifiLock?.release()
        } catch (_: Throwable) {
        }
        try {
            cpuLock?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
        cpuLock = null
        HostRuntime.session = null
        val ip = NetInfo.ipv4() ?: "Tidak ada Wi-Fi"
        HostRuntime.update {
            it.copy(
                running = false,
                clients = emptyList(),
                fps = 0,
                bitrateKbps = 0,
                videoLabel = "—",
                ip = ip,
                error = error,
                lastInput = "Belum ada input"
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(audio: Boolean) {
        val notification = buildNotification("Menyiapkan pantulan…")
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (audio && Build.VERSION.SDK_INT >= 30) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val ui = HostRuntime.state.value
        val text = if (ui.running) {
            "${ui.ip}:${ui.httpPort} · ${ui.clients.size} layar · ${ui.fps} fps"
        } else {
            "Berhenti"
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, MirrorHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RetroBitApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_retrobit)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun showBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return
        if (bubble != null) return
        val view = TextView(this).apply {
            text = "STOP"
            setTextColor(0xFF1A0C04.toInt())
            textSize = 11f
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply {
                cornerRadius = 99f
                setColor(0xFFFF7A1A.toInt())
            }
            setOnClickListener { shutdown(null) }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 28
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            bubble = view
        } catch (t: Throwable) {
            Log.w(TAG, "bubble ${t.message}")
        }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        bubble = null
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun hasMicPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "RetroBitHost"
        private const val NOTIF_ID = 42
        const val ACTION_START = "dev.retrobit.mirror.START"
        const val ACTION_STOP = "dev.retrobit.mirror.STOP"
        const val ACTION_MODE = "dev.retrobit.mirror.MODE"
        const val EXTRA_MODE = "mode"

        fun setMode(context: Context, mode: String) {
            context.startService(
                Intent(context, MirrorHostService::class.java)
                    .setAction(ACTION_MODE)
                    .putExtra(EXTRA_MODE, mode)
            )
        }
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_AUDIO = "audio"

        fun start(context: Context, code: Int, data: Intent, preset: QualityPreset, audio: Boolean) {
            val intent = Intent(context, MirrorHostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_PRESET, preset.id)
                putExtra(EXTRA_AUDIO, audio)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorHostService::class.java).setAction(ACTION_STOP))
        }
    }
}
