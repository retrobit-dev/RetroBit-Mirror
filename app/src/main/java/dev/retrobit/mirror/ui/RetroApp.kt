package dev.retrobit.mirror.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrobit.mirror.client.MirrorClient
import dev.retrobit.mirror.host.HostRuntime
import dev.retrobit.mirror.model.QualityPreset
import dev.retrobit.mirror.util.DeviceHints

private sealed interface Route {
    data object Home : Route
    data object Host : Route
    data object Guide : Route
    data object Client : Route
    data object Play : Route
}

@Composable
fun RetroApp(
    client: MirrorClient,
    onStartMirror: (QualityPreset, Boolean) -> Unit,
    onStopMirror: () -> Unit
) {
    RetroTheme {
        var route by rememberSaveable { mutableStateOf("home") }
        val host by HostRuntime.state.collectAsState()
        RetroBackdrop {
            when (route) {
                "host" -> HostScreen(
                    onBack = { route = "home" },
                    onStart = onStartMirror,
                    onStop = onStopMirror
                )
                "guide" -> GuideScreen(onBack = { route = "home" })
                "client" -> ClientBrowseScreen(
                    client = client,
                    onBack = { route = "home" },
                    onConnected = { route = "play" }
                )
                "play" -> PlayScreen(
                    client = client,
                    onExit = {
                        client.disconnect()
                        route = "client"
                    }
                )
                else -> HomeScreen(
                    running = host.running,
                    onHost = { route = "host" },
                    onClient = { route = "client" },
                    onGuide = { route = "guide" }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(running: Boolean, onHost: () -> Unit, onClient: () -> Unit, onGuide: () -> Unit) {
    val infinix = DeviceHints.isInfinix()
    val huawei = DeviceHints.isHuawei()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(22.dp)
    ) {
        Wordmark()
        Spacer(Modifier.height(8.dp))
        Text("Mirror", color = Paper, fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pantulkan Infinix GT 30 Pro ke Huawei MatePad Mini. Gamepad, keyboard, sentuhan, dan GT Trigger dalam satu sesi LAN.",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
        if (running) {
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                Text("Pantulan sedang hidup", color = Ok, fontWeight = FontWeight.Bold)
                Text("Buka game di ponsel. Layar tetap mengalir di latar depan.", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        RoleCard(
            kicker = if (infinix) "DISARANKAN DI PERANGKAT INI" else "HOST",
            title = "GT 30 Pro",
            body = "Tangkap layar, terima input, biarkan GT Trigger fisik tetap menembak game.",
            accent = Amber,
            onClick = onHost
        )
        Spacer(Modifier.height(12.dp))
        RoleCard(
            kicker = if (huawei) "DISARANKAN DI PERANGKAT INI" else "LAYAR",
            title = "MatePad Mini",
            body = "Tampilkan mirror, gamepad virtual, keyboard, dan stik Bluetooth. HarmonyOS cukup lewat browser.",
            accent = Cyan,
            onClick = onClient
        )
        Spacer(Modifier.height(18.dp))
        GhostButton("Cara pakai GT Trigger", onGuide, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Text("LAN saja · PIN · tidak ada akun", color = Muted, fontSize = 12.sp)
        Text(DeviceHints.modelLabel(), color = Muted.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun RoleCard(kicker: String, title: String, body: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Text(kicker, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        Text(title, color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun GuideScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("← Kembali", color = Amber, modifier = Modifier.clickable(onClick = onBack), fontWeight = FontWeight.Bold)
        Text("Cara main", color = Paper, fontSize = 32.sp, fontWeight = FontWeight.Black)
        GuideBlock(
            "1",
            "Pasang di GT 30 Pro",
            "Build APK di Android Studio, lalu sideload ke ponsel. Izinkan pemasangan dari sumber tak dikenal. Buka RetroBit, pilih GT 30 Pro, mulai pantulan, dan pilih seluruh layar."
        )
        GuideBlock(
            "2",
            "MatePad Mini",
            "HarmonyOS 5 tidak menjalankan APK Android. Di browser tablet buka http://IP-PONSEL:28731 lalu masukkan PIN. Kalau tabletmu masih bisa pasang APK, pilih peran Layar di aplikasi yang sama — jalur itu lebih rendah latensi."
        )
        GuideBlock(
            "3",
            "Shizuku untuk stik",
            "Ketukan sederhana bisa lewat aksesibilitas. Stick analog, tombol tahan, dan keyboard butuh Shizuku: pasang Shizuku, mulai lewat debugging nirkabel, lalu izinkan RetroBit. Tanpa ini game tetap kelihatan, tetapi kontrol analog tidak stabil."
        )
        GuideBlock(
            "4",
            "GT Trigger",
            "Game berjalan di ponsel, jadi sensor GT Trigger XOS tetap menembak layar ponsel. Jangan pasang overlay di atas game. Di MatePad, kalibrasi tombol GT L dan GT R ke koordinat skill yang sama dengan pemetaan XOS. Pegang ponsel sambil melihat tablet, atau taruh ponsel di stand dan main penuh dari tablet."
        )
        GuideBlock(
            "5",
            "Agar tidak putus",
            "Samakan Wi-Fi, atau jadikan hotspot ponsel kalau router memisahkan perangkat. Matikan optimalisasi baterai untuk RetroBit. Di Game Space, jangan blokir jaringan aplikasi ini. Suara biarkan dari speaker ponsel supaya tidak gema."
        )
    }
}

@Composable
private fun GuideBlock(index: String, title: String, body: String) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(28.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Amber),
                contentAlignment = Alignment.Center
            ) {
                Text(index, color = Ink, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(body, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

internal fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
