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
import dev.retrobit.mirror.input.RetroBitAccessibilityService
import dev.retrobit.mirror.model.Control
import dev.retrobit.mirror.model.MirrorView
import dev.retrobit.mirror.model.Profiles
import dev.retrobit.mirror.model.ViewMode
import dev.retrobit.mirror.util.DisplayInfo
import dev.retrobit.mirror.util.Prefs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Controller on the GT 30 Pro. Drawn as an accessibility overlay when possible
 * so MediaProjection sends the game, not these panels, to the MatePad.
 */
class HostDeck(private val context: Context) {
    private var wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val windows = ArrayList<Pair<View, WindowManager>>()
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

    fun show(mode: String, hideCenter: Boolean, access: RetroBitAccessibilityService?, includePad: Boolean) {
        hide()
        profile = loadProfile()
        val sizeContext: Context = access ?: context
        val size = DisplayInfo.currentSize(sizeContext)
        val landscape = size.first > size.second
        val panelW = if (landscape) {
            val minW = (size.first / 6).coerceAtLeast(dp(108))
            val maxW = (size.first / 4).coerceAtLeast(minW)
            dp(168).coerceIn(minW, maxW)
        } else {
            (size.first / 4).coerceAtLeast(dp(92))
        }
        val panelManager = (if (includePad || access == null) context else access).getSystemService(WindowManager::class.java)
        val excludePad = !includePad && access != null
        if (access != null && (hideCenter || (mode == ViewMode.EXTENDED && !includePad))) {
            val cover = coverView(if (hideCenter || mode == ViewMode.EXTENDED) "Layar ada di MatePad" else "Layar penuh ada di MatePad")
            val coverManager = access.getSystemService(WindowManager::class.java)
            place(cover, panelW, 0, size.first - panelW * 2, size.second, touchable = false, excluded = true, manager = coverManager)
        }
        place(panel(panelW, size.second, left = true, landscape = landscape), 0, 0, panelW, size.second, true, excludePad, panelManager)
        place(panel(panelW, size.second, left = false, landscape = landscape), size.first - panelW, 0, panelW, size.second, true, excludePad, panelManager)
        wm = panelManager
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
        windows.forEach { (view, manager) ->
            try {
                manager.removeView(view)
            } catch (_: Throwable) {
            }
        }
        windows.clear()
    }

    private fun loadProfile() = Profiles.fromJson(Prefs.profiles(context))
        .firstOrNull { it.id == Prefs.activeProfile(context) && it.controls.isNotEmpty() }
        ?: Profiles.moba()

    private fun panel(width: Int, height: Int, left: Boolean, landscape: Boolean): View {
        val root = FrameLayout(context)
        root.background = plate(0xE610141C.toInt(), 0)
        val controls = profile.controls.filter { control ->
            val visual = if (control.linked) control.x else control.ux
            if (left) visual < 0.5f else visual >= 0.5f
        }
        val widgets = ArrayList<Pair<View, Int>>()
        controls.forEach { control ->
            if (control.kind == "dpad") {
                widgets += dpad(control) to (width * 0.72f).toInt()
            } else if (control.kind == "stick") {
                widgets += stick(control) to (width * if (landscape) 0.78f else 0.7f).toInt()
            } else if (control.id in setOf("a", "b", "x", "y") && !left) {
                // Face buttons are grouped below.
            } else {
                widgets += button(control) to (width * 0.42f).toInt().coerceAtLeast(dp(44))
            }
        }
        if (!left) {
            val faces = profile.controls.filter { it.id in setOf("a", "b", "x", "y") }
            if (faces.isNotEmpty()) widgets += faceGrid(faces, width) to (width * 0.78f).toInt()
        }
        val gap = dp(8)
        val usable = (height - dp(56)).coerceAtLeast(dp(80))
        val raw = widgets.sumOf { it.second } + gap * (widgets.size - 1).coerceAtLeast(0)
        val scale = if (raw > usable) usable.toFloat() / raw.toFloat() else 1f
        var y = dp(46) + ((usable - (raw * scale).toInt()) / 2).coerceAtLeast(0)
        widgets.forEach { (view, size) ->
            val box = (size * scale).toInt().coerceAtMost(width - dp(10)).coerceAtLeast(dp(36))
            val lp = FrameLayout.LayoutParams(box, if (view is LinearLayout) FrameLayout.LayoutParams.WRAP_CONTENT else box)
            lp.leftMargin = (width - box) / 2
            lp.topMargin = y.coerceAtMost((height - dp(40)).coerceAtLeast(0))
            root.addView(view, lp)
            y += box + (gap * scale).toInt()
        }
        val bar = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.addView(chip(profile.name) {
            val all = Profiles.fromJson(Prefs.profiles(context)).filter { it.controls.isNotEmpty() }.ifEmpty { Profiles.defaults() }
            val idx = all.indexOfFirst { it.id == profile.id }.coerceAtLeast(0)
            val next = all[(idx + 1) % all.size]
            Prefs.setActiveProfile(context, next.id)
            profile = next
            mapper.reset()
            main.post { MirrorHostService.setMode(context, Prefs.viewMode(context)) }
        })
        if (left) {
            bar.addView(chip(if (Prefs.hideWhenConnected(context)) "Tampil di HP" else "Sembunyi") {
                Prefs.setHideWhenConnected(context, !Prefs.hideWhenConnected(context))
                main.post { MirrorHostService.setMode(context, Prefs.viewMode(context)) }
            })
        } else {
            bar.addView(chip(if (MirrorView.includesPad(Prefs.mirrorView(context))) "Layar saja" else "Layar+Pad") {
                val next = if (MirrorView.includesPad(Prefs.mirrorView(context))) MirrorView.SCREEN else MirrorView.BOTH
                Prefs.setMirrorView(context, next)
                main.post { MirrorHostService.setMode(context, Prefs.viewMode(context)) }
            })
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun faceGrid(faces: List<Control>, width: Int): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val order = listOf("y", "x", "b", "a")
        val ordered = order.mapNotNull { id -> faces.firstOrNull { it.id == id } }
        ordered.chunked(2).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { control ->
                val view = button(control)
                val size = (width * 0.36f).toInt().coerceAtLeast(dp(42))
                line.addView(view, LinearLayout.LayoutParams(size, size).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            grid.addView(line)
        }
        return grid
    }

    private fun dpad(control: Control): View {
        val box = FrameLayout(context)
        listOf("du" to Gravity.TOP, "dd" to Gravity.BOTTOM, "dl" to Gravity.START, "dr" to Gravity.END).forEach { (id, gravity) ->
            val view = button(control.copy(id = id, label = id.drop(1).uppercase(), kind = "button"))
            val lp = FrameLayout.LayoutParams(dp(40), dp(40), gravity)
            box.addView(view, lp)
        }
        return box
    }

    private fun stick(control: Control): View {
        return object : View(context) {
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
                        apply((dx * scale) / max, (dy * scale) / max, true)
                    }
                    else -> apply(0f, 0f, false)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun button(control: Control): TextView {
        return TextView(context).apply {
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

    private fun coverView(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(0xFFF4F1EA.toInt())
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = plate(0xF007080C.toInt(), 0)
        }
    }

    private fun place(
        view: View,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        touchable: Boolean,
        excluded: Boolean,
        manager: WindowManager = wm
    ) {
        val type = if (excluded) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= 26) {
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
            this.y = y
        }
        manager.addView(view, params)
        windows += view to manager
    }

    private fun plate(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
