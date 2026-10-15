package dev.retrobit.mirror.client

import dev.retrobit.mirror.model.Control
import dev.retrobit.mirror.model.PadIds
import dev.retrobit.mirror.model.Profile
import kotlin.math.abs
import kotlin.math.hypot

class PadMapper(private val send: (id: Int, action: String, x: Float, y: Float) -> Unit) {
    private val down = HashSet<Int>()
    private val look = HashMap<String, Look>()
    private val buttonWas = HashMap<String, Boolean>()

    fun reset() {
        down.toList().forEach { id -> send(id, "up", 0.5f, 0.5f) }
        down.clear()
        look.clear()
        buttonWas.clear()
    }

    fun emit(profile: Profile, state: PadState) {
        profile.controls.forEach { control ->
            when (control.kind) {
                "stick" -> emitStick(control, state)
                "button" -> emitButton(control, pressed(control.id, state))
                "dpad" -> emitDpad(control, state)
            }
        }
        listOf("a", "b", "x", "y", "lb", "rb", "lt", "rt", "start", "select", "l3", "r3", "du", "dd", "dl", "dr")
            .filter { id -> profile.controls.none { it.id == id || (it.kind == "dpad" && id.startsWith("d")) } }
            .forEach { id ->
                if (pressed(id, state)) {
                    // unmapped hardware buttons still reported as keys by the activity
                }
            }
    }

    private fun emitStick(control: Control, state: PadState) {
        val ax = if (control.id == "rs") state.rx else state.lx
        val ay = if (control.id == "rs") state.ry else state.ly
        val pid = if (control.id == "rs") PadIds.RS else PadIds.LS
        if (control.look) {
            val tracker = look.getOrPut(control.id) { Look(control.x, control.y) }
            tracker.originX = control.x
            tracker.originY = control.y
            val mag = hypot(ax, ay)
            if (mag < DEADZONE) {
                if (tracker.down) {
                    send(pid, "up", tracker.x, tracker.y)
                    tracker.down = false
                    down.remove(pid)
                }
                tracker.x = control.x
                tracker.y = control.y
                return
            }
            if (!tracker.down) {
                tracker.x = control.x
                tracker.y = control.y
                send(pid, "down", tracker.x, tracker.y)
                tracker.down = true
                down.add(pid)
            }
            tracker.x = (tracker.x + ax * 0.035f).coerceIn(control.x - control.r, control.x + control.r)
            tracker.y = (tracker.y + ay * 0.035f).coerceIn(control.y - control.r, control.y + control.r)
            if (abs(tracker.x - control.x) > control.r * 0.9f || abs(tracker.y - control.y) > control.r * 0.9f) {
                send(pid, "up", tracker.x, tracker.y)
                tracker.x = control.x
                tracker.y = control.y
                send(pid, "down", tracker.x, tracker.y)
            } else {
                send(pid, "move", tracker.x, tracker.y)
            }
            return
        }
        val mag = hypot(ax, ay)
        if (mag < DEADZONE) {
            if (down.contains(pid)) {
                send(pid, "up", control.x, control.y)
                down.remove(pid)
            }
            return
        }
        val adj = ((mag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        val px = (control.x + ax / mag * adj * control.r).coerceIn(0.01f, 0.99f)
        val py = (control.y + ay / mag * adj * control.r).coerceIn(0.01f, 0.99f)
        if (!down.contains(pid)) {
            send(pid, "down", px, py)
            down.add(pid)
        } else {
            send(pid, "move", px, py)
        }
    }

    private fun emitButton(control: Control, pressed: Boolean) {
        val was = buttonWas[control.id] == true
        val pid = PadIds.button(control.id)
        if (pressed && !was) {
            send(pid, "down", control.x, control.y)
            down.add(pid)
            if (control.mode == "tap") {
                send(pid, "up", control.x, control.y)
                down.remove(pid)
            }
        } else if (!pressed && was && control.mode != "tap") {
            send(pid, "up", control.x, control.y)
            down.remove(pid)
        }
        buttonWas[control.id] = pressed
    }

    private fun emitDpad(control: Control, state: PadState) {
        val dirs = listOf(
            "du" to (0f to -1f),
            "dd" to (0f to 1f),
            "dl" to (-1f to 0f),
            "dr" to (1f to 0f)
        )
        dirs.forEach { (id, vec) ->
            val fake = control.copy(id = id, kind = "button", x = control.x + vec.first * control.r, y = control.y + vec.second * control.r)
            emitButton(fake, pressed(id, state))
        }
    }

    private fun pressed(id: String, state: PadState): Boolean {
        if (state.buttons.contains(id)) return true
        if (id == "lt" && state.lt > 0.55f) return true
        if (id == "rt" && state.rt > 0.55f) return true
        return false
    }

    private class Look(var originX: Float, var originY: Float) {
        var x = originX
        var y = originY
        var down = false
    }

    companion object {
        private const val DEADZONE = 0.14f
    }
}

class PadState {
    var lx = 0f
    var ly = 0f
    var rx = 0f
    var ry = 0f
    var lt = 0f
    var rt = 0f
    val buttons = HashSet<String>()
    var touchLs = false
    var touchRs = false

    fun setButton(id: String, down: Boolean) {
        if (down) buttons.add(id) else buttons.remove(id)
    }
}
