package dev.retrobit.mirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF07080C)
val InkRaised = Color(0xFF14161E)
val Amber = Color(0xFFFF7A1A)
val AmberDeep = Color(0xFFFF5A00)
val Cyan = Color(0xFF3EE0FF)
val Gold = Color(0xFFFFC14A)
val Paper = Color(0xFFF4F1EA)
val Muted = Color(0xFF9A9388)
val Danger = Color(0xFFFF4D6A)
val Ok = Color(0xFF3DDC97)
val Line = Color.White.copy(alpha = 0.08f)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A0C04),
    secondary = Cyan,
    background = Ink,
    surface = InkRaised,
    onBackground = Paper,
    onSurface = Paper,
    error = Danger
)

@Composable
fun RetroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

@Composable
fun RetroBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .drawBehind {
                drawCircle(Amber.copy(alpha = 0.16f), radius = size.minDimension * 0.72f, center = Offset(0f, 0f))
                drawCircle(Cyan.copy(alpha = 0.08f), radius = size.minDimension * 0.55f, center = Offset(size.width, size.height))
            },
        content = content
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun Wordmark() {
    Text(
        "RETROBIT",
        color = Amber,
        fontWeight = FontWeight.Black,
        letterSpacing = 3.sp,
        fontSize = 12.sp
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF1A0C04), disabledContainerColor = Amber.copy(alpha = 0.35f))
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Mono(text: String, color: Color = Paper, size: Int = 16) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
}
