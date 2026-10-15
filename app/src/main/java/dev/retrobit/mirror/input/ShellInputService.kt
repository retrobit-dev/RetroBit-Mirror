package dev.retrobit.mirror.input

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import dev.retrobit.mirror.IShellInput
import java.util.LinkedHashMap

/**
 * Runs inside the Shizuku shell process (uid 2000 or 0), where InputManager.injectInputEvent
 * is allowed. The app process only forwards coordinates.
 */
class ShellInputService() : IShellInput.Stub() {

    @Suppress("unused")
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private class Finger(val id: Int, var x: Float, var y: Float, val downTime: Long)

    private val fingers = LinkedHashMap<Int, Finger>()
    private val keyDown = HashMap<Int, Long>()
    private val lock = Any()
    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var broken = false

    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun ping(): String {
        return "ok uid=${android.os.Process.myUid()} inject=${!broken}"
    }

    override fun pointer(action: Int, pointerId: Int, x: Float, y: Float): Boolean {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            when (action) {
                0 -> {
                    if (fingers.containsKey(pointerId)) {
                        fingers.getValue(pointerId).x = x
                        fingers.getValue(pointerId).y = y
                        return injectMotion(MotionEvent.ACTION_MOVE, -1, now)
                    }
                    if (fingers.size >= 10) return false
                    fingers[pointerId] = Finger(pointerId, x, y, now)
                    val index = fingers.keys.indexOf(pointerId)
                    val masked = if (fingers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
                    return injectMotion(masked, index, now)
                }
                1 -> {
                    val finger = fingers[pointerId] ?: return pointer(0, pointerId, x, y)
                    finger.x = x
                    finger.y = y
                    return injectMotion(MotionEvent.ACTION_MOVE, -1, now)
                }
                2 -> {
                    val index = fingers.keys.indexOf(pointerId)
                    if (index < 0) return false
                    fingers.getValue(pointerId).x = x
                    fingers.getValue(pointerId).y = y
                    val masked = if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
                    val ok = injectMotion(masked, index, now)
                    fingers.remove(pointerId)
                    return ok
                }
                else -> {
                    val ok = if (fingers.isEmpty()) true else injectMotion(MotionEvent.ACTION_CANCEL, -1, now)
                    fingers.clear()
                    return ok
                }
            }
        }
    }

    override fun key(action: Int, keyCode: Int, meta: Int, source: Int): Boolean {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val downTime = if (action == KeyEvent.ACTION_DOWN) {
                now.also { keyDown[keyCode] = it }
            } else {
                keyDown.remove(keyCode) ?: now
            }
            val event = KeyEvent(downTime, now, action, keyCode, 0, meta, -1, 0, 0, source)
            val ok = inject(event)
            if (!ok) {
                try {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString())).waitFor()
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "keyevent fallback ${t.message}")
                }
            }
            return ok
        }
    }

    override fun text(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        return try {
            val escaped = buildString(value.length + 8) {
                for (ch in value) {
                    when (ch) {
                        ' ' -> append("%s")
                        '%' -> append("%%")
                        '\n' -> append("%s")
                        else -> append(ch)
                    }
                }
            }
            val proc = Runtime.getRuntime().exec(arrayOf("input", "text", escaped))
            proc.waitFor() == 0
        } catch (t: Throwable) {
            Log.w(TAG, "text ${t.message}")
            false
        }
    }

    private fun injectMotion(masked: Int, actionIndex: Int, now: Long): Boolean {
        val list = fingers.values.toList()
        if (list.isEmpty()) return false
        val props = Array(list.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = list[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(list.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = list[i].x
                y = list[i].y
                pressure = if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_POINTER_UP) 0f else 1f
                size = 0.04f
            }
        }
        val action = if (actionIndex >= 0) {
            masked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            masked
        }
        val event = MotionEvent.obtain(
            list[0].downTime,
            now,
            action,
            list.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        return try {
            val ok = inject(event)
            if (!ok && list.size == 1 && (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_UP)) {
                shellTap(list[0].x, list[0].y)
            } else {
                ok
            }
        } finally {
            event.recycle()
        }
    }

    private fun shellTap(x: Float, y: Float): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString())).waitFor()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "tap fallback ${t.message}")
            false
        }
    }

    private fun inject(event: InputEvent): Boolean {
        if (broken) return false
        return try {
            val manager = inputManager ?: obtainManager().also { inputManager = it }
            val method = injectMethod ?: resolveInject(manager).also { injectMethod = it }
            val result = if (method.parameterTypes.size >= 3) {
                method.invoke(manager, event, 0, -1)
            } else {
                method.invoke(manager, event, 0)
            }
            result as? Boolean ?: true
        } catch (t: Throwable) {
            broken = true
            Log.e(TAG, "injectInputEvent unavailable: ${t.message}")
            false
        }
    }

    private fun obtainManager(): Any {
        val cls = Class.forName("android.hardware.input.InputManager")
        val method = cls.getDeclaredMethod("getInstance")
        method.isAccessible = true
        return method.invoke(null) ?: error("InputManager null")
    }

    private fun resolveInject(manager: Any): java.lang.reflect.Method {
        val methods = manager.javaClass.methods.filter { it.name == "injectInputEvent" }
        val two = methods.firstOrNull { it.parameterTypes.size == 2 }
        if (two != null) return two
        val three = methods.firstOrNull { it.parameterTypes.size == 3 }
        if (three != null) return three
        error("injectInputEvent missing")
    }

    companion object {
        private const val TAG = "RetroBitShell"
    }
}
