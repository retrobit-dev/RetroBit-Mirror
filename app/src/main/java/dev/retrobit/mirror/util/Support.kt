package dev.retrobit.mirror.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.retrobit.mirror.model.ViewMode
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

object NetInfo {
    fun ipv4(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            val score = when {
                name.startsWith("wlan") || name.startsWith("wifi") -> 0
                name.startsWith("ap") || name.contains("softap") -> 1
                name.startsWith("eth") -> 2
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.contains("data") -> 5
                else -> 3
            }
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("169.254.")) continue
                    candidates += score to host
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    fun subnetBroadcast(ipv4: String): String? {
        val parts = ipv4.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }
}

object DisplayInfo {
    fun realSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    fun dpi(context: Context): Int = context.resources.displayMetrics.densityDpi.coerceAtLeast(120)

    fun evenFit(srcW: Int, srcH: Int, maxSide: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 1280 to 720
        val longSide = maxOf(srcW, srcH)
        val scale = minOf(1f, maxSide.toFloat() / longSide.toFloat())
        val w = ((srcW * scale).toInt().coerceAtLeast(2) / 2) * 2
        val h = ((srcH * scale).toInt().coerceAtLeast(2) / 2) * 2
        return w to h
    }
}

object DeviceHints {
    fun isInfinix(): Boolean {
        val blob = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}".lowercase()
        return blob.contains("infinix") || blob.contains("xos")
    }

    fun isHuawei(): Boolean {
        val blob = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return blob.contains("huawei") || blob.contains("honor")
    }

    fun modelLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun deviceName(): String = when {
        isHuawei() -> "MatePad Mini"
        isInfinix() -> "GT 30 Pro"
        else -> Build.MODEL.ifBlank { "RetroBit" }
    }
}

object Intents {
    fun accessibility(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shizukuPage(context: Context) {
        val uri = Uri.parse("https://shizuku.rikka.app/")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun wirelessDebug(context: Context) {
        val tries = listOf(
            Intent("android.settings.ADB_WIRELESS_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }

    fun battery(context: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    fun overlay(context: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        if (Settings.canDrawOverlays(context)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

object Prefs {
    private const val FILE = "retrobit"

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun pin(context: Context): String {
        val saved = sp(context).getString("pin", null)
        if (!saved.isNullOrBlank() && saved.length in 4..6 && saved.all { it.isDigit() }) return saved
        return newPin().also { setPin(context, it) }
    }

    fun setPin(context: Context, pin: String) {
        sp(context).edit().putString("pin", pin.filter { it.isDigit() }.take(6)).apply()
    }

    fun newPin(): String = (1000 + Random.nextInt(9000)).toString()

    fun preset(context: Context): String = sp(context).getString("preset", QualitySafe.BALANCED) ?: QualitySafe.BALANCED

    fun setPreset(context: Context, id: String) {
        sp(context).edit().putString("preset", id).apply()
    }

    fun audio(context: Context): Boolean = sp(context).getBoolean("audio", false)
    fun setAudio(context: Context, on: Boolean) = sp(context).edit().putBoolean("audio", on).apply()

    fun bubble(context: Context): Boolean = sp(context).getBoolean("bubble", false)
    fun setBubble(context: Context, on: Boolean) = sp(context).edit().putBoolean("bubble", on).apply()

    fun profiles(context: Context): String? = sp(context).getString("profiles", null)
    fun setProfiles(context: Context, json: String) = sp(context).edit().putString("profiles", json).apply()

    fun activeProfile(context: Context): String = sp(context).getString("activeProfile", "moba") ?: "moba"
    fun setActiveProfile(context: Context, id: String) = sp(context).edit().putString("activeProfile", id).apply()

    fun lastHost(context: Context): String = sp(context).getString("lastHost", "") ?: ""
    fun setLastHost(context: Context, host: String) = sp(context).edit().putString("lastHost", host).apply()

    fun lastPin(context: Context): String = sp(context).getString("lastPin", "") ?: ""
    fun setLastPin(context: Context, pin: String) = sp(context).edit().putString("lastPin", pin).apply()

    fun viewMode(context: Context): String = ViewMode.normalize(sp(context).getString("viewMode", ViewMode.SPLIT))

    fun setViewMode(context: Context, mode: String) {
        sp(context).edit().putString("viewMode", ViewMode.normalize(mode)).apply()
    }

    fun hostName(context: Context): String {
        val saved = sp(context).getString("hostName", null)
        if (!saved.isNullOrBlank()) return saved
        return DeviceHints.deviceName()
    }

    fun setHostName(context: Context, name: String) {
        sp(context).edit().putString("hostName", name.trim().take(32)).apply()
    }
}

object QualitySafe {
    const val BALANCED = "BALANCED"
}

object Locks {
    fun wifi(context: Context): WifiManager.WifiLock {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return wm.createWifiLock(mode, "retrobit:stream").apply { setReferenceCounted(false) }
    }

    fun multicast(context: Context): WifiManager.MulticastLock {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        return wm.createMulticastLock("retrobit:disco").apply { setReferenceCounted(false) }
    }

    fun cpu(context: Context): PowerManager.WakeLock {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retrobit:cpu").apply { setReferenceCounted(false) }
    }
}
