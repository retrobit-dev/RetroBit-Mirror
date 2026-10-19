package dev.retrobit.mirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import dev.retrobit.mirror.host.HostRuntime
import dev.retrobit.mirror.input.InputHub
import dev.retrobit.mirror.input.ShizukuGate
import dev.retrobit.mirror.model.MirrorView
import dev.retrobit.mirror.model.QualityPreset
import dev.retrobit.mirror.model.ViewMode
import dev.retrobit.mirror.util.Intents
import dev.retrobit.mirror.util.NetInfo
import dev.retrobit.mirror.util.Prefs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostScreen(
    onBack: () -> Unit,
    onStart: (QualityPreset, Boolean) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val state by HostRuntime.state.collectAsState()
    var preset by rememberSaveable { mutableStateOf(Prefs.preset(context)) }
    var audio by rememberSaveable { mutableStateOf(Prefs.audio(context)) }
    var bubble by rememberSaveable { mutableStateOf(Prefs.bubble(context)) }
    var viewMode by rememberSaveable { mutableStateOf(Prefs.viewMode(context)) }
    var mirrorView by rememberSaveable { mutableStateOf(Prefs.mirrorView(context)) }
    var hidePhone by rememberSaveable { mutableStateOf(Prefs.hideWhenConnected(context)) }
    var copied by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        ShizukuGate.refreshStatus(context)
        InputHub.refreshGates()
        if (!state.running) {
            HostRuntime.update {
                it.copy(
                    ip = NetInfo.ipv4() ?: "Tidak ada Wi-Fi",
                    pin = Prefs.pin(context),
                    preset = Prefs.preset(context),
                    audio = Prefs.audio(context),
                    shizuku = ShizukuGate.status,
                    accessibility = dev.retrobit.mirror.input.RetroBitAccessibilityService.instance != null
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("← Peran", color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack))
        Text("Host GT 30 Pro", color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("Game tetap di ponsel. MatePad hanya menampilkan layar, tanpa gamepad.", color = Muted, fontSize = 14.sp)

        if (!state.error.isNullOrBlank()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(state.error ?: "", color = Danger, fontSize = 14.sp)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Text(if (state.running) "SIARAN HIDUP" else "SIAP DI LAN", color = if (state.running) Ok else Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))
            Mono(state.pin.ifBlank { "····" }, Gold, 40)
            Spacer(Modifier.height(4.dp))
            Text("PIN sesi · masukkan ini di MatePad", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            val url = if (state.ip.contains('.')) "http://${state.ip}:${state.httpPort}" else "Sambungkan Wi-Fi dulu"
            Mono(url, Paper, 14)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Salin URL", {
                    if (state.ip.contains('.')) {
                        copyText(context, "url", "http://${state.ip}:${state.httpPort}")
                        copied = "URL disalin"
                    }
                })
                GhostButton("PIN baru", {
                    if (!state.running) {
                        val pin = Prefs.newPin()
                        Prefs.setPin(context, pin)
                        HostRuntime.update { it.copy(pin = pin) }
                    }
                })
            }
            if (copied.isNotEmpty()) Text(copied, color = Ok, fontSize = 12.sp)
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                Text("${state.videoLabel} · ${state.fps} fps · ${state.bitrateKbps} kbps", color = Muted, fontSize = 12.sp)
                Text("Input: ${state.lastInput}", color = Muted, fontSize = 12.sp)
                Text(
                    "Klien: ${if (state.clients.isEmpty()) "belum ada" else state.clients.joinToString()}",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }

        if (state.running) {
            PrimaryButton("Berhenti pantulan", onStop, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Mulai pantulan", {
                Prefs.setPreset(context, preset)
                Prefs.setAudio(context, audio)
                Prefs.setViewMode(context, viewMode)
                Prefs.setMirrorView(context, mirrorView)
                if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(context)) {
                    Intents.overlay(context)
                }
                onStart(QualityPreset.fromId(preset), audio)
            }, Modifier.fillMaxWidth())
        }

        Text("Tata letak di ponsel", color = Paper, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ViewMode.SPLIT to "Belah", ViewMode.EXTENDED to "Layar tambahan").forEach { (id, label) ->
                val selected = viewMode == id
                Text(
                    label,
                    color = if (selected) Ink else Paper,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Cyan else androidx.compose.ui.graphics.Color.White.copy(0.06f))
                        .clickable {
                            viewMode = id
                            Prefs.setViewMode(context, id)
                            if (state.running) dev.retrobit.mirror.host.MirrorHostService.setMode(context, id)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Text(
            if (viewMode == ViewMode.EXTENDED) {
                "Ponsel hanya kontrol. Pada dialog tangkap layar pilih aplikasinya, bukan seluruh layar. MatePad menerima aplikasi itu penuh."
            } else {
                "Potret: kiri dan kanan 1/4 kontrol, tengah game. Landscape: panel kontrol lebih sempit supaya area game lebih lebar. MatePad menerima game, bukan tombol."
            },
            color = Muted,
            fontSize = 12.sp
        )

        Text("Tampilan di MatePad", color = Paper, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MirrorView.SCREEN to "Layar saja", MirrorView.BOTH to "Layar + gamepad").forEach { (id, label) ->
                val selected = mirrorView == id
                Text(
                    label,
                    color = if (selected) Ink else Paper,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Gold else androidx.compose.ui.graphics.Color.White.copy(0.06f))
                        .clickable {
                            mirrorView = id
                            Prefs.setMirrorView(context, id)
                            if (id == MirrorView.BOTH && android.os.Build.VERSION.SDK_INT >= 23 &&
                                !android.provider.Settings.canDrawOverlays(context)
                            ) {
                                Intents.overlay(context)
                            }
                            if (state.running) dev.retrobit.mirror.host.MirrorHostService.setMode(context, viewMode)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Text(
            if (mirrorView == MirrorView.BOTH) {
                "MatePad menampilkan game dan panel kontrol. Ketukan tetap dilakukan di GT 30 Pro."
            } else {
                "MatePad hanya menampilkan game. Gamepad tetap di ponsel dan tidak ikut terkirim."
            },
            color = Muted,
            fontSize = 12.sp
        )

        Text("Kualitas", color = Paper, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityPreset.entries.forEach { item ->
                val selected = preset == item.id
                Text(
                    item.label,
                    color = if (selected) Ink else Paper,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Amber else androidx.compose.ui.graphics.Color.White.copy(0.06f))
                        .clickable {
                            preset = item.id
                            Prefs.setPreset(context, item.id)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Text(QualityPreset.fromId(preset).detail, color = Muted, fontSize = 12.sp)

        Panel(Modifier.fillMaxWidth()) {
            ToggleRow("Suara di tablet", "Mati secara bawaan supaya tidak gema dengan speaker ponsel.", audio) {
                audio = it
                Prefs.setAudio(context, it)
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "Sembunyikan layar ponsel saat tersambung",
                "Gambar tetap jalan ke MatePad. Aktifkan aksesibilitas dulu, supaya penutup tidak ikut tertangkap.",
                hidePhone
            ) {
                hidePhone = it
                Prefs.setHideWhenConnected(context, it)
                if (state.running) dev.retrobit.mirror.host.MirrorHostService.setMode(context, viewMode)
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Gelembung STOP", "Tombol kecil di atas. Matikan kalau menghalangi game.", bubble) {
                bubble = it
                Prefs.setBubble(context, it)
                if (it) Intents.overlay(context)
            }
        }

        InjectionCard()
        GtCard()
        Panel(Modifier.fillMaxWidth()) {
            Text("Whitelist baterai", color = Paper, fontWeight = FontWeight.Bold)
            Text("XOS suka memotong jaringan aplikasi di balik game. Izinkan RetroBit berjalan bebas.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            GhostButton("Buka pengaturan baterai", { Intents.battery(context) })
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Ink)
        )
    }
}

@Composable
private fun InjectionCard() {
    val context = LocalContext.current
    val state by HostRuntime.state.collectAsState()
    val shizukuLabel = when (state.shizuku) {
        ShizukuGate.READY -> "Shizuku siap"
        ShizukuGate.DENIED -> "Shizuku belum diizinkan"
        ShizukuGate.NOT_RUNNING -> "Shizuku terpasang, belum jalan"
        ShizukuGate.NOT_INSTALLED -> "Shizuku belum terpasang"
        else -> "Memeriksa Shizuku…"
    }
    val color = when {
        state.shizuku == ShizukuGate.READY -> Ok
        state.accessibility -> Gold
        else -> Danger
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("METODE INPUT", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        Text(shizukuLabel, color = Paper, fontWeight = FontWeight.Bold)
        Text(
            if (state.accessibility) "Aksesibilitas aktif · cadangan ketuk/geser" else "Aksesibilitas belum aktif",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Shizuku di ponsel ini untuk stick dan tombol tahan. Aksesibilitas juga menyembunyikan kontrol dari tangkapan layar, jadi MatePad melihat game, bukan tombol.",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Shizuku", { Intents.shizukuPage(context) })
            GhostButton("Izinkan", { ShizukuGate.requestPermission(context) })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Debug nirkabel", { Intents.wirelessDebug(context) })
            GhostButton("Aksesibilitas", { Intents.accessibility(context) })
        }
    }
}

@Composable
private fun GtCard() {
    Panel(Modifier.fillMaxWidth()) {
        Text("GT TRIGGER", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        Text("Sensor di rangka ponsel, dan game juga di ponsel.", color = Paper, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Mode belah membuka tengah layar, jadi GT Trigger tetap bisa menembak game. Overlay kiri dan kanan menelan ketukan yang jatuh di situ.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tombol GT di pinggir ponsel mengirim ketukan ke titik yang dikalibrasi. MatePad tidak punya gamepad.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}
