package dev.retrobit.mirror.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.retrobit.mirror.client.PadMapper
import dev.retrobit.mirror.client.PadState
import dev.retrobit.mirror.input.InputHub
import dev.retrobit.mirror.model.Control
import dev.retrobit.mirror.model.Profiles
import dev.retrobit.mirror.model.ViewMode
import dev.retrobit.mirror.util.DisplayInfo
import dev.retrobit.mirror.util.Prefs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Controller drawn on the GT 30 Pro. Split mode leaves the center half of the
 * screen open so the game, and GT Trigger, stay visible. Extended mode covers
 * the phone with controls only.
 */
class HostDeck(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val windows = ArrayList<View>()
    private val pad = PadState()
    private val mapper = PadMapper { id, action, x, y ->
        val code = when (action) {
            "down" -> 0
            "move" -> 1
            "up" -> 2
            else -> 3
        }
        InputHub.pointer(code, id, x * InputHub.displayW, y * InputHub.displayH)
    }
    private var profile = loadProfile()
    private val tick = object : Runnable {
        override fun run() {
            mapper.emit(profile, pad)
            main.postDelayed(this, 16)
        }
    }

    fun show(mode: String) {
        hide()
        profile = loadProfile()
        val size = DisplayInfo.realSize(context)
        val panelW = size.first / 4
        if (mode == ViewMode.EXTENDED) {
            add(labelWindow(size.first, size.second, 0, "Layar penuh ada di MatePad"), touchable = false)
        }
        add(panel(panelW, size.second, 0, left = true), touchable = true)
        add(panel(panelW, size.second, size.first - panelW, left = false), touchable = true)
        main.post(tick)
    }

    fun hide() {
        main.removeCallbacks(tick)
        mapper.reset()
        pad.lx = 0f
        pad.ly = 0f
        pad.rx = 0f
        pad.ry = 0f
        pad.buttons.clear()
        windows.forEach { view ->
            try {
                wm.removeView(view)
            } catch (_: Throwable) {
            }
        }
        windows.clear()
    }

    private fun loadProfile() = Profiles.fromJson(Prefs.profiles(context))
        .firstOrNull { it.id == Prefs.activeProfile(context) }
        ?: Profiles.moba()

    private fun panel(width: Int, height: Int, x: Int, left: Boolean): View {
        val root = FrameLayout(context)
        root.background = plate(0xCC10141C.toInt(), 0)
        root.tag = x
        val controls = profile.controls.filter { control ->
            val visual = if (control.linked) control.x else control.ux
            if (left) visual < 0.5f else visual >= 0.5f
        }
        controls.forEach { control ->
            if (control.kind == "dpad") {
                listOf("du" to "U", "dd" to "D", "dl" to "L", "dr" to "R").forEachIndexed { index, (id, label) ->
                    val view = button(control.copy(id = id, label = label, kind = "button"))
                    val size = (width * 0.34f).toInt().coerceAtLeast(dp(36))
                    val lp = FrameLayout.LayoutParams(size, size)
                    lp.leftMargin = if (index % 2 == 0) dp(8) else width - size - dp(8)
                    lp.topMargin = (control.uy * height + (index / 2) * (size + dp(6))).toInt().coerceIn(0, (height - size).coerceAtLeast(0))
                    root.addView(view, lp)
                }
            } else {
                val view = if (control.kind == "stick") stick(control) else button(control)
                val size = (width * if (control.kind == "stick") 0.72f else 0.48f).toInt()
                val lp = FrameLayout.LayoutParams(size, size)
                lp.leftMargin = ((width - size) / 2).coerceAtLeast(0)
                lp.topMargin = (control.uy * height - size / 2f).toInt().coerceIn(dp(8), (height - size - dp(8)).coerceAtLeast(dp(8)))
                root.addView(view, lp)
            }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.addView(chip(profile.name) {
            val all = Profiles.fromJson(Prefs.profiles(context)).ifEmpty { Profiles.defaults() }
            val idx = all.indexOfFirst { it.id == profile.id }.coerceAtLeast(0)
            val next = all[(idx + 1) % all.size]
            Prefs.setActiveProfile(context, next.id)
            profile = next
            mapper.reset()
            main.post { show(Prefs.viewMode(context)) }
        })
        if (left) {
            bar.addView(chip(if (Prefs.viewMode(context) == ViewMode.EXTENDED) "Belah" else "Tambah") {
                val next = if (Prefs.viewMode(context) == ViewMode.EXTENDED) ViewMode.SPLIT else ViewMode.EXTENDED
                main.post { MirrorHostService.setMode(context, next) }
            })
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun stick(control: Control): View {
        return object : View(context) {
            private var kx = 0f
            private var ky = 0f
            init {
                background = plate(if (control.id == "rs") 0x553EE0FF else 0x55FF7A1A, dp(99))
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val max = min(width, height) / 2f * 0.62f
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - width / 2f
                        val dy = event.y - height / 2f
                        val mag = hypot(dx, dy).coerceAtLeast(0.001f)
                        val scale = min(1f, max / mag)
                        kx = dx * scale
                        ky = dy * scale
                        apply(kx / max, ky / max, true)
                    }
                    else -> {
                        kx = 0f
                        ky = 0f
                        apply(0f, 0f, false)
                    }
                }
                return true
            }

            private fun apply(x: Float, y: Float, active: Boolean) {
                if (control.id == "rs") {
                    pad.touchRs = active
                    pad.rx = x
                    pad.ry = y
                } else {
                    pad.touchLs = active
                    pad.lx = x
                    pad.ly = y
                }
            }
        }
    }

    private fun button(control: Control): View {
        val label = TextView(context).apply {
            text = control.label
            setTextColor(if (control.gt) 0xFFFFC14A.toInt() else 0xFFFF7A1A.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = plate(0x33FFFFFF, if (control.gt) dp(12) else dp(99))
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> pad.setButton(control.id, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pad.setButton(control.id, false)
                }
                true
            }
        }
        return label
    }

    private fun chip(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(0xFF07080C.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = plate(0xFFFF7A1A.toInt(), dp(99))
            setOnClickListener { onClick() }
        }
    }

    private fun labelWindow(width: Int, height: Int, x: Int, text: String): View {
        return TextView(context).apply {
            this.text = text
            setTextColor(0xFFF4F1EA.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = plate(0xF0101218.toInt(), 0)
            tag = x
        }
    }

    private fun add(view: View, touchable: Boolean) {
        val size = DisplayInfo.realSize(context)
        val width = if (view is TextView && view.gravity == Gravity.CENTER && view.background != null && view.tag == null) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else if (view.tag is Int && view is TextView) {
            size.first
        } else {
            size.first / 4
        }
        val height = if (width == WindowManager.LayoutParams.WRAP_CONTENT) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            size.second
        }
        val x = (view.tag as? Int) ?: 0
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(width, height, type, flags, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = 0
        }
        wm.addView(view, params)
        windows += view
    }

    private fun plate(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
