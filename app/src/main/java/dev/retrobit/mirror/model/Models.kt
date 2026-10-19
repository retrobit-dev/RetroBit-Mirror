package dev.retrobit.mirror.model

import org.json.JSONArray
import org.json.JSONObject

object ViewMode {
    const val SPLIT = "SPLIT"
    const val EXTENDED = "EXTENDED"

    fun normalize(mode: String?): String = if (mode == EXTENDED) EXTENDED else SPLIT

    fun crop(mode: String): Float = if (mode == EXTENDED) 0f else 0.25f
}

enum class QualityPreset(
    val id: String,
    val label: String,
    val detail: String,
    val maxSide: Int,
    val fps: Int,
    val bpp: Float
) {
    COMPETITIVE("COMPETITIVE", "Kompetitif", "Latensi rendah · 60 fps", 1280, 60, 0.075f),
    BALANCED("BALANCED", "Seimbang", "Tajam dan stabil · 45 fps", 1600, 45, 0.11f),
    QUALITY("QUALITY", "Tajam", "Dekat resolusi asli · 30 fps", 2048, 30, 0.14f);

    companion object {
        fun fromId(id: String?): QualityPreset = QualityPreset.values().firstOrNull { it.id == id } ?: BALANCED
    }
}

data class Control(
    val kind: String,
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val ux: Float = x,
    val uy: Float = y,
    val r: Float = 0.10f,
    val mode: String = "hold",
    val look: Boolean = false,
    val gt: Boolean = false,
    val linked: Boolean = true
)

data class Profile(
    val id: String,
    val name: String,
    val blurb: String,
    val controls: List<Control>
)

data class HostUiState(
    val running: Boolean = false,
    val ip: String = "—",
    val tcpPort: Int = 28730,
    val httpPort: Int = 28731,
    val pin: String = "····",
    val preset: String = QualityPreset.BALANCED.id,
    val audio: Boolean = false,
    val bubble: Boolean = false,
    val clients: List<String> = emptyList(),
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val videoLabel: String = "—",
    val injection: String = "none",
    val shizuku: String = "unknown",
    val accessibility: Boolean = false,
    val error: String? = null,
    val lastInput: String = "Belum ada input",
    val hint: String? = null,
    val viewMode: String = ViewMode.SPLIT
)

data class FoundHost(
    val name: String,
    val host: String,
    val tcpPort: Int,
    val httpPort: Int,
    val seenAt: Long
)

object Profiles {
    fun defaults(): List<Profile> = listOf(touch(), moba(), fps(), racing())

    fun touch() = Profile(
        id = "touch",
        name = "Sentuh",
        blurb = "Jari langsung di layar. Tanpa gamepad.",
        controls = emptyList()
    )

    fun moba() = Profile(
        id = "moba",
        name = "MOBA",
        blurb = "Stick kiri jalan, tombol skill di kanan, GT untuk spell.",
        controls = listOf(
            Control("stick", "ls", "LS", 0.16f, 0.74f, r = 0.11f),
            Control("button", "a", "A", 0.90f, 0.74f, mode = "tap"),
            Control("button", "b", "B", 0.80f, 0.84f, mode = "tap"),
            Control("button", "x", "X", 0.80f, 0.58f, mode = "tap"),
            Control("button", "y", "Y", 0.70f, 0.70f, mode = "tap"),
            Control("button", "lb", "LB", 0.16f, 0.18f),
            Control("button", "rb", "RB", 0.84f, 0.18f),
            Control("button", "lt", "GT L", 0.10f, 0.42f, ux = 0.07f, uy = 0.50f, gt = true, linked = false),
            Control("button", "rt", "GT R", 0.90f, 0.42f, ux = 0.93f, uy = 0.50f, gt = true, linked = false),
            Control("button", "start", "Start", 0.56f, 0.08f, mode = "tap"),
            Control("button", "select", "Select", 0.44f, 0.08f, mode = "tap"),
            Control("dpad", "dpad", "D", 0.16f, 0.40f, r = 0.07f)
        )
    )

    fun fps() = Profile(
        id = "fps",
        name = "FPS",
        blurb = "Stick kiri gerak, kanan lihat, GT L aim, GT R tembak.",
        controls = listOf(
            Control("stick", "ls", "LS", 0.16f, 0.72f, r = 0.11f),
            Control("stick", "rs", "RS", 0.58f, 0.48f, ux = 0.82f, uy = 0.62f, r = 0.07f, look = true, linked = false),
            Control("button", "a", "Lompat", 0.90f, 0.46f),
            Control("button", "b", "Jongkok", 0.78f, 0.46f),
            Control("button", "x", "Isi", 0.70f, 0.78f, mode = "tap"),
            Control("button", "y", "Senjata", 0.90f, 0.28f, mode = "tap"),
            Control("button", "lt", "GT L", 0.74f, 0.70f, ux = 0.07f, uy = 0.46f, gt = true, linked = false),
            Control("button", "rt", "GT R", 0.88f, 0.72f, ux = 0.93f, uy = 0.46f, gt = true, linked = false),
            Control("button", "lb", "LB", 0.16f, 0.16f),
            Control("button", "rb", "RB", 0.84f, 0.16f),
            Control("button", "start", "Start", 0.56f, 0.08f, mode = "tap"),
            Control("button", "select", "Select", 0.44f, 0.08f, mode = "tap")
        )
    )

    fun racing() = Profile(
        id = "racing",
        name = "Balap",
        blurb = "Kiri-kanan setir, GT R nitro, A rem.",
        controls = listOf(
            Control("button", "dl", "Kiri", 0.12f, 0.62f),
            Control("button", "dr", "Kanan", 0.28f, 0.62f),
            Control("button", "a", "Rem", 0.78f, 0.70f),
            Control("button", "rt", "GT R", 0.90f, 0.55f, ux = 0.93f, uy = 0.42f, gt = true, linked = false),
            Control("button", "lt", "GT L", 0.10f, 0.40f, ux = 0.07f, uy = 0.42f, gt = true, linked = false),
            Control("button", "b", "Nitro", 0.88f, 0.78f),
            Control("button", "start", "Start", 0.56f, 0.08f, mode = "tap"),
            Control("button", "select", "Select", 0.44f, 0.08f, mode = "tap")
        )
    )

    fun toJson(profiles: List<Profile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            val controls = JSONArray()
            p.controls.forEach { c ->
                controls.put(
                    JSONObject()
                        .put("kind", c.kind)
                        .put("id", c.id)
                        .put("label", c.label)
                        .put("x", c.x.toDouble())
                        .put("y", c.y.toDouble())
                        .put("ux", c.ux.toDouble())
                        .put("uy", c.uy.toDouble())
                        .put("r", c.r.toDouble())
                        .put("mode", c.mode)
                        .put("look", c.look)
                        .put("gt", c.gt)
                        .put("linked", c.linked)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("blurb", p.blurb)
                    .put("controls", controls)
            )
        }
        return arr.toString()
    }

    fun fromJson(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return defaults()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Profile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val controlsJson = o.optJSONArray("controls") ?: JSONArray()
                val controls = ArrayList<Control>(controlsJson.length())
                for (j in 0 until controlsJson.length()) {
                    val c = controlsJson.getJSONObject(j)
                    val x = c.optDouble("x", 0.5).toFloat()
                    val y = c.optDouble("y", 0.5).toFloat()
                    controls += Control(
                        kind = c.optString("kind", "button"),
                        id = c.optString("id", "b$j"),
                        label = c.optString("label", "B"),
                        x = x,
                        y = y,
                        ux = c.optDouble("ux", x.toDouble()).toFloat(),
                        uy = c.optDouble("uy", y.toDouble()).toFloat(),
                        r = c.optDouble("r", 0.1).toFloat(),
                        mode = c.optString("mode", "hold"),
                        look = c.optBoolean("look", false),
                        gt = c.optBoolean("gt", false),
                        linked = c.optBoolean("linked", true)
                    )
                }
                out += Profile(
                    id = o.optString("id", "custom$i"),
                    name = o.optString("name", "Kustom"),
                    blurb = o.optString("blurb", ""),
                    controls = controls
                )
            }
            if (out.isEmpty()) defaults() else out
        } catch (_: Throwable) {
            defaults()
        }
    }
}

object PadIds {
    const val LS = 3
    const val RS = 4
    fun button(id: String): Int = when (id) {
        "a" -> 5
        "b" -> 6
        "x" -> 7
        "y" -> 8
        "lb" -> 10
        "rb" -> 11
        "lt" -> 12
        "rt" -> 13
        "start" -> 14
        "select" -> 15
        "l3" -> 16
        "r3" -> 17
        "du" -> 18
        "dd" -> 19
        "dl" -> 20
        "dr" -> 21
        else -> 9
    }
}
