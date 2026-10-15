package dev.retrobit.mirror.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class RetroBitAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        InputHub.refreshGates()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        InputHub.refreshGates()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 48)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(40, 1500))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun nav(which: String): Boolean {
        val action = when (which) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return false
        }
        return performGlobalAction(action)
    }

    companion object {
        @Volatile
        var instance: RetroBitAccessibilityService? = null
            private set

        fun gestureFor(action: Int, id: Int, x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            val track = tracks.getOrPut(id) { Track(x, y) }
            return when (action) {
                0 -> {
                    track.startX = x
                    track.startY = y
                    track.x = x
                    track.y = y
                    track.down = true
                    svc.tap(x, y)
                }
                1 -> {
                    track.x = x
                    track.y = y
                    true
                }
                2 -> {
                    val moved = hypot(track.x - track.startX, track.y - track.startY) > 18f
                    track.down = false
                    if (moved) svc.swipe(track.startX, track.startY, track.x, track.y, 90) else true
                }
                else -> {
                    tracks.clear()
                    true
                }
            }
        }

        private val tracks = HashMap<Int, Track>()

        private class Track(var startX: Float, var startY: Float) {
            var x = startX
            var y = startY
            var down = false
        }
    }
}
