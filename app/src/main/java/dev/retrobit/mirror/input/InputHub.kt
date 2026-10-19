package dev.retrobit.mirror.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import dev.retrobit.mirror.host.HostRuntime
import org.json.JSONObject

object InputHub {
    @Volatile
    var displayW: Int = 1080

    @Volatile
    var displayH: Int = 2400

    private var lastUiNote = 0L

    fun refreshGates() {
        val shell = ShizukuGate.service
        val injection = when {
            shell != null -> "shizuku"
            RetroBitAccessibilityService.instance != null -> "accessibility"
            else -> "none"
        }
        HostRuntime.update {
            it.copy(
                injection = if (HostRuntime.state.value.running) injection else it.injection,
                shizuku = ShizukuGate.status,
                accessibility = RetroBitAccessibilityService.instance != null
            )
        }
    }

    fun injectionName(): String {
        return when {
            ShizukuGate.service != null -> "shizuku"
            RetroBitAccessibilityService.instance != null -> "accessibility"
            else -> "none"
        }
    }

    fun handlePayload(json: String) {
        val root = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return
        }
        if (root.optString("type") == "keyframe") {
            HostRuntime.session?.requestKey()
            return
        }
        if (root.optString("type") == "view") {
            HostRuntime.onMirrorView?.invoke(root.optString("mode"))
            return
        }
        val events = root.optJSONArray("events") ?: return
        var note = ""
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "pointer" -> {
                    val x = (e.optDouble("x", 0.0) * displayW).toFloat().coerceIn(0f, (displayW - 1).toFloat())
                    val y = (e.optDouble("y", 0.0) * displayH).toFloat().coerceIn(0f, (displayH - 1).toFloat())
                    val action = when (e.optString("action")) {
                        "down" -> 0
                        "move" -> 1
                        "up" -> 2
                        else -> 3
                    }
                    pointer(action, e.optInt("id", 0), x, y)
                    if (action != 1) note = "sentuh ${x.toInt()},${y.toInt()}"
                }
                "tap" -> {
                    val x = (e.optDouble("x", 0.0) * displayW).toFloat()
                    val y = (e.optDouble("y", 0.0) * displayH).toFloat()
                    tap(x, y)
                    note = "ketuk ${x.toInt()},${y.toInt()}"
                }
                "key" -> {
                    val down = e.optString("action") != "up"
                    val code = e.optInt("code", 0)
                    val source = if (e.optString("source") == "gamepad") {
                        InputDevice.SOURCE_GAMEPAD
                    } else {
                        InputDevice.SOURCE_KEYBOARD
                    }
                    key(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, e.optInt("meta", 0), source)
                    note = "tombol $code"
                }
                "text" -> {
                    text(e.optString("text"))
                    note = "teks"
                }
                "nav" -> {
                    nav(e.optString("which"))
                    note = e.optString("which")
                }
            }
        }
        if (note.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastUiNote > 180) {
                lastUiNote = now
                HostRuntime.update { it.copy(lastInput = note) }
            }
        }
    }

    fun pointer(action: Int, id: Int, x: Float, y: Float): Boolean {
        val shell = ShizukuGate.service
        if (shell != null) {
            try {
                return shell.pointer(action, id, x, y)
            } catch (_: Throwable) {
                ShizukuGate.bind(null)
            }
        }
        return RetroBitAccessibilityService.gestureFor(action, id, x, y)
    }

    fun tap(x: Float, y: Float): Boolean {
        val shell = ShizukuGate.service
        if (shell != null) {
            val id = 9
            return try {
                shell.pointer(0, id, x, y) && shell.pointer(2, id, x, y)
            } catch (_: Throwable) {
                false
            }
        }
        return RetroBitAccessibilityService.instance?.tap(x, y) == true
    }

    fun key(action: Int, code: Int, meta: Int, source: Int): Boolean {
        if (action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH)) {
            if (ShizukuGate.service == null) {
                val which = when (code) {
                    KeyEvent.KEYCODE_HOME -> "home"
                    KeyEvent.KEYCODE_APP_SWITCH -> "recents"
                    else -> "back"
                }
                if (RetroBitAccessibilityService.instance?.nav(which) == true) return true
            }
        }
        val shell = ShizukuGate.service ?: return false
        return try {
            shell.key(action, code, meta, source)
        } catch (_: Throwable) {
            false
        }
    }

    fun text(value: String): Boolean {
        val shell = ShizukuGate.service ?: return false
        return try {
            shell.text(value)
        } catch (_: Throwable) {
            false
        }
    }

    fun nav(which: String): Boolean {
        val code = when (which) {
            "home" -> KeyEvent.KEYCODE_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH
            "volumeUp" -> KeyEvent.KEYCODE_VOLUME_UP
            "volumeDown" -> KeyEvent.KEYCODE_VOLUME_DOWN
            else -> KeyEvent.KEYCODE_BACK
        }
        if (which == "volumeUp" || which == "volumeDown") {
            return key(KeyEvent.ACTION_DOWN, code, 0, InputDevice.SOURCE_KEYBOARD) &&
                key(KeyEvent.ACTION_UP, code, 0, InputDevice.SOURCE_KEYBOARD)
        }
        if (ShizukuGate.service != null) {
            val source = InputDevice.SOURCE_KEYBOARD
            return key(KeyEvent.ACTION_DOWN, code, 0, source) && key(KeyEvent.ACTION_UP, code, 0, source)
        }
        return RetroBitAccessibilityService.instance?.nav(which) == true
    }
}
