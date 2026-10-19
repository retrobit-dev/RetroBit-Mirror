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
            "Game berjalan di GT 30 Pro. MatePad Mini hanya menampilkan layarnya. Semua kontrol ada di ponsel.",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
        if (running) {
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                Text("Pantulan sedang hidup", color = Ok, fontWeight = FontWeight.Bold)
                Text("Buka game di ponsel. MatePad menampilkan layar tanpa gamepad.", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        if (huawei && !infinix) {
            DisplayRoleCard(huawei, onClient)
            Spacer(Modifier.height(12.dp))
            HostRoleCard(infinix, onHost)
        } else {
            HostRoleCard(infinix, onHost)
            Spacer(Modifier.height(12.dp))
            DisplayRoleCard(huawei, onClient)
        }
        Spacer(Modifier.height(18.dp))
        GhostButton("Cara pakai GT Trigger", onGuide, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Text("LAN saja · PIN · tidak ada akun", color = Muted, fontSize = 12.sp)
        Text(DeviceHints.modelLabel(), color = Muted.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun HostRoleCard(infinix: Boolean, onClick: () -> Unit) {
    RoleCard(
        kicker = if (infinix) "DISARANKAN DI PERANGKAT INI" else "HOST",
        title = "GT 30 Pro",
        body = "Game dan semua kontrol ada di sini. Kiri dan kanan untuk gamepad, tengah untuk layar game.",
        accent = Amber,
        onClick = onClick
    )
}

@Composable
private fun DisplayRoleCard(huawei: Boolean, onClick: () -> Unit) {
    RoleCard(
        kicker = if (huawei) "DISARANKAN DI PERANGKAT INI" else "LAYAR",
        title = "MatePad Mini",
        body = "Hanya menampilkan layar dari GT 30 Pro. Tidak ada gamepad di tablet.",
        accent = Cyan,
        onClick = onClick
    )
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
            "GT 30 Pro sebagai host",
            "Pasang APK di ponsel, pilih GT 30 Pro, mulai pantulan, lalu pilih seluruh layar untuk mode belah. Game tetap dibuka di ponsel. Kontrol muncul di kiri dan kanan."
        )
        GuideBlock(
            "2",
            "MatePad hanya layar",
            "Pasang APK yang sama dan pilih MatePad Mini, atau buka URL dari ponsel di browser. Pilih Layar saja atau Layar + gamepad. Semua ketukan tetap dilakukan di GT 30 Pro."
        )
        GuideBlock(
            "3",
            "Dua tata letak",
            "Belah: potret memakai 1/4 kiri dan 1/4 kanan untuk kontrol. Landscape memakai panel lebih sempit. Layar tambahan: ponsel hanya kontrol, MatePad menampilkan aplikasi penuh. Saat tersambung, layar ponsel bisa disembunyikan tanpa mematikan gambar di tablet. Aktifkan aksesibilitas di GT 30 Pro."
        )
        GuideBlock(
            "4",
            "Shizuku di ponsel",
            "Stick dan tombol tahan disuntikkan di GT 30 Pro. Pasang Shizuku di ponsel, mulai lewat debugging nirkabel, lalu izinkan RetroBit."
        )
        GuideBlock(
            "5",
            "GT Trigger",
            "Trigger fisik hanya menembak layar ponsel. Mode belah membiarkan tengah terbuka supaya tembakan itu sampai ke game. Jangan tutup titik skill dengan overlay."
        )
        GuideBlock(
            "6",
            "Kalau Wi-Fi yang sama gagal",
            "Beberapa router tidak mengizinkan ponsel dan tablet saling bicara. Di GT 30 Pro buka Hotspot, sambungkan MatePad ke hotspot itu, lalu pindai kode QR atau buka URL hotspot. PIN tetap diminta."
        )
        GuideBlock(
            "7",
            "Layout gamepad",
            "Di host, pilih profil MOBA, FPS, atau Balap. Atur layout dengan menyeret tombol, atau sembunyikan gamepad. Chip Gamepad di atas layar menampilkannya lagi. Reset mengembalikan belah kiri-kanan."
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
