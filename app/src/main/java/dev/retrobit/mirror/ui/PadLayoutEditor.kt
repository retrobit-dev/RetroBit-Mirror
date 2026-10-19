package dev.retrobit.mirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrobit.mirror.model.Profiles
import dev.retrobit.mirror.util.Prefs
import kotlin.math.roundToInt

@Composable
fun PadLayoutEditor(profileId: String) {
    val context = LocalContext.current
    val profile = remember(profileId) {
        Profiles.fromJson(Prefs.profiles(context)).firstOrNull { it.id == profileId && it.controls.isNotEmpty() }
            ?: Profiles.defaults().first { it.controls.isNotEmpty() }
    }
    val saved = remember(profileId) { Prefs.deckLayout(context, profileId) }
    var positions by remember(profileId) {
        mutableStateOf(profile.controls.associate { control ->
            val pos = saved[control.id]
            control.id to (pos ?: (control.ux to control.uy))
        })
    }
    if (profile.controls.isEmpty()) {
        Text("Profil ini tidak punya gamepad.", color = Muted, fontSize = 13.sp)
        return
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Ink)
            .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        Text(
            "Seret tombol. Posisi ini hanya di ponsel.",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 8.dp)
        )
        profile.controls.forEach { control ->
            val pos = positions[control.id] ?: (control.ux to control.uy)
            val box = if (control.kind == "stick" || control.kind == "dpad") 54.dp else 42.dp
            val boxPx = with(density) { box.toPx() }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (pos.first * widthPx - boxPx / 2f).roundToInt(),
                            (pos.second * heightPx - boxPx / 2f).roundToInt()
                        )
                    }
                    .size(box)
                    .clip(if (control.gt) RoundedCornerShape(10.dp) else CircleShape)
                    .background(if (control.gt) Gold.copy(alpha = 0.85f) else Amber.copy(alpha = 0.9f))
                    .pointerInput(control.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val current = positions[control.id] ?: pos
                            val next = (current.first + drag.x / widthPx).coerceIn(0.06f, 0.94f) to
                                (current.second + drag.y / heightPx).coerceIn(0.06f, 0.94f)
                            positions = positions + (control.id to next)
                            Prefs.setDeckLayout(context, profileId, positions)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(control.label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
