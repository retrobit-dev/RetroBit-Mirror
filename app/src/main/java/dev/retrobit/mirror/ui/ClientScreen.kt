package dev.retrobit.mirror.ui

import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import dev.retrobit.mirror.client.HardwareInput
import dev.retrobit.mirror.client.MirrorClient
import dev.retrobit.mirror.client.PadMapper
import dev.retrobit.mirror.client.PadState
import dev.retrobit.mirror.model.Control
import dev.retrobit.mirror.model.Profile
import dev.retrobit.mirror.model.Profiles
import dev.retrobit.mirror.protocol.Proto
import dev.retrobit.mirror.util.DeviceHints
import dev.retrobit.mirror.util.Prefs
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun ClientBrowseScreen(client: MirrorClient, onBack: () -> Unit, onConnected: () -> Unit) {
    val context = LocalContext.current
    val hosts by client.hosts.collectAsState()
    val stats by client.stats.collectAsState()
    var manual by remember { mutableStateOf(Prefs.lastHost(context)) }
    var pin by remember { mutableStateOf(Prefs.lastPin(context)) }

    LaunchedEffect(Unit) { client.startScan(context) }
    DisposableEffect(Unit) { onDispose { client.stopScan() } }
    LaunchedEffect(stats.phase) {
        if (stats.phase == "play") onConnected()
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
        Text("Layar MatePad", color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("Cari GT 30 Pro di Wi-Fi yang sama. Tablet ini hanya menampilkan, tidak mengontrol.", color = Muted)
        if (stats.detail.isNotBlank()) Text(stats.detail, color = if (stats.phase == "error") Danger else Gold)

        Panel(Modifier.fillMaxWidth()) {
            Text(if (hosts.isEmpty()) "Mencari pancaran…" else "Host terlihat", color = Paper, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (hosts.isEmpty()) {
                Text("Kalau tidak muncul, ketik IP manual. Beberapa router memblokir siaran UDP.", color = Muted, fontSize = 13.sp)
            }
            hosts.forEach { host ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            Prefs.setLastHost(context, host.host)
                            Prefs.setLastPin(context, pin)
                            client.connect(host.host, host.tcpPort, pin, DeviceHints.deviceName())
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(host.name, color = Paper, fontWeight = FontWeight.Bold)
                        Mono("${host.host}:${host.tcpPort}", Muted, 12)
                    }
                    Text("Sambung", color = Amber, fontWeight = FontWeight.Bold)
                }
            }
        }

        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IP host") },
            singleLine = true,
            colors = fieldColors()
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = fieldColors()
        )
        PrimaryButton("Sambungkan manual", {
            val raw = manual.trim()
            val host = raw.substringBefore(":")
            val port = raw.substringAfter(":", Proto.TCP.toString()).toIntOrNull() ?: Proto.TCP
            if (host.isNotBlank() && pin.length >= 4) {
                Prefs.setLastHost(context, host)
                Prefs.setLastPin(context, pin)
                client.connect(host, port, pin, DeviceHints.deviceName())
            }
        }, Modifier.fillMaxWidth(), enabled = pin.length >= 4 && manual.isNotBlank())
        Text("Gamepad, keyboard, dan GT Trigger ada di GT 30 Pro. Jangan harapkan kontrol di tablet.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun PlayScreen(client: MirrorClient, onExit: () -> Unit) {
    val context = LocalContext.current
    val stats by client.stats.collectAsState()
    val jpegBytes by client.jpeg.collectAsState()
    var profiles by remember { mutableStateOf(Profiles.fromJson(Prefs.profiles(context))) }
    var profileId by remember { mutableStateOf(Prefs.activeProfile(context)) }
    var showPad by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var calibrate by remember { mutableStateOf<String?>(null) }
    var opacity by remember { mutableFloatStateOf(0.86f) }
    val pad = remember { PadState() }
    val mapper = remember { PadMapper { id, action, x, y -> client.sendPointer(id, action, x, y) } }
    val profile = profiles.firstOrNull { it.id == profileId } ?: profiles.first()
    val profileNow = rememberUpdatedState(profile)
    val showPadNow = rememberUpdatedState(showPad)

    DisposableEffect(Unit) {
        HardwareInput.captureKeyboard = false
        HardwareInput.onKey = { _, _, _, _ -> }
        HardwareInput.onAxes = { lx, ly, rx, ry, lt, rt, hatX, hatY ->
            if (!pad.touchLs) {
                pad.lx = lx
                pad.ly = ly
            }
            if (!pad.touchRs) {
                pad.rx = rx
                pad.ry = ry
            }
            pad.lt = lt
            pad.rt = rt
            pad.setButton("du", hatY < -0.5f)
            pad.setButton("dd", hatY > 0.5f)
            pad.setButton("dl", hatX < -0.5f)
            pad.setButton("dr", hatX > 0.5f)
        }
        onDispose {
            HardwareInput.onKey = null
            HardwareInput.onAxes = null
            mapper.reset()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (showPadNow.value) mapper.emit(profileNow.value, pad)
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
            val aspect = stats.videoW.toFloat() / stats.videoH.coerceAtLeast(1)
            val maxW = maxWidth
            val maxH = maxHeight
            val width = if (maxW / maxH > aspect) maxH * aspect else maxW
            val height = width / aspect
            Box(Modifier.size(width, height).align(Alignment.Center)) {
                val bitmap = remember(jpegBytes) {
                    jpegBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
                if (bitmap != null) {
                    Image(
                        bitmap,
                        contentDescription = "Layar GT 30 Pro",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = false
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                    st.setDefaultBufferSize(stats.videoW.coerceAtLeast(2), stats.videoH.coerceAtLeast(2))
                                    client.attachSurface(android.view.Surface(st))
                                    client.requestKeyframe()
                                }
                                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    client.detachSurface()
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.surfaceTexture?.setDefaultBufferSize(stats.videoW.coerceAtLeast(2), stats.videoH.coerceAtLeast(2))
                    }
                )
                if (false) {
                    TouchLayer(
                        Modifier.fillMaxSize().zIndex(1f),
                        key = calibrate ?: "",
                        onPointer = { id, action, x, y ->
                            if (calibrate != null && action == "up") {
                                profiles = profiles.map { p ->
                                    if (p.id != profile.id) p
                                    else p.copy(controls = p.controls.map { c ->
                                        if (c.id == calibrate) c.copy(x = x, y = y, linked = false) else c
                                    })
                                }
                                Prefs.setProfiles(context, Profiles.toJson(profiles))
                                calibrate = null
                            } else {
                                client.sendPointer(id, action, x, y)
                            }
                        }
                    )
                }
                if (showPad) {
                    PadOverlay(
                        profile = profile,
                        opacity = opacity,
                        editing = editing,
                        modifier = Modifier.fillMaxSize().zIndex(2f),
                        onStick = { id, x, y, active ->
                            if (id == "rs") {
                                pad.touchRs = active
                                pad.rx = x
                                pad.ry = y
                            } else {
                                pad.touchLs = active
                                pad.lx = x
                                pad.ly = y
                            }
                        },
                        onButton = { id, down -> pad.setButton(id, down) },
                        onMove = { id, dx, dy ->
                            profiles = profiles.map { p ->
                                if (p.id != profile.id) p
                                else p.copy(controls = p.controls.map { c ->
                                    if (c.id != id) c
                                    else {
                                        val ux = (c.ux + dx).coerceIn(0.04f, 0.96f)
                                        val uy = (c.uy + dy).coerceIn(0.04f, 0.96f)
                                        if (c.linked) c.copy(x = ux, y = uy, ux = ux, uy = uy) else c.copy(ux = ux, uy = uy)
                                    }
                                })
                            }
                            Prefs.setProfiles(context, Profiles.toJson(profiles))
                        }
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp).zIndex(4f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Tutup", onExit)
                Text(
                    buildString {
                        append(stats.hostName.ifBlank { "Host" })
                        if (stats.pingMs >= 0) append("  ${stats.pingMs} ms")
                        append("  ${stats.fps} fps")
                    },
                    color = Paper,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                Text("Layar saja", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Kontrol ada di GT 30 Pro. Tablet ini tidak mengirim sentuhan atau gamepad.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (calibrate != null) {
                Text("Ketuk layar game untuk titik tembak $calibrate", color = Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (editing) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Kalibrasi GT L") { calibrate = "lt"; editing = false }
                    Chip("Kalibrasi GT R") { calibrate = "rt"; editing = false }
                    Chip("Reset") {
                        profiles = Profiles.defaults()
                        Prefs.setProfiles(context, Profiles.toJson(profiles))
                    }
                    Chip(if (opacity > 0.7f) "Pudar" else "Terang") {
                        opacity = if (opacity > 0.7f) 0.4f else 0.9f
                    }
                }
            }
        }



        if (showKeys) {
            KeyboardSheet(
                Modifier.align(Alignment.BottomCenter).zIndex(5f),
                onKey = { code, down -> client.sendKey(if (down) "down" else "up", code) },
                onText = { client.sendText(it) },
                onClose = { showKeys = false }
            )
        }
    }
}

@Composable
private fun TouchLayer(modifier: Modifier, key: Any, onPointer: (id: Int, action: String, x: Float, y: Float) -> Unit) {
    val slots = remember { HashMap<Long, Int>() }
    Box(
        modifier.pointerInput(key) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { change ->
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                        val action = when {
                            !change.previousPressed && change.pressed -> "down"
                            change.previousPressed && !change.pressed -> "up"
                            change.pressed -> "move"
                            else -> "up"
                        }
                        val id = if (action == "down") {
                            val free = (0..2).firstOrNull { slot -> slot !in slots.values } ?: 0
                            slots[change.id.value] = free
                            free
                        } else {
                            slots[change.id.value] ?: 0
                        }
                        if (action == "up") slots.remove(change.id.value)
                        onPointer(id, action, nx, ny)
                        change.consume()
                    }
                }
            }
        }
    )
}

@Composable
private fun PadOverlay(
    profile: Profile,
    opacity: Float,
    editing: Boolean,
    modifier: Modifier,
    onStick: (id: String, x: Float, y: Float, active: Boolean) -> Unit,
    onButton: (id: String, down: Boolean) -> Unit,
    onMove: (id: String, dx: Float, dy: Float) -> Unit
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val parentW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val parentH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        profile.controls.forEach { control ->
            val size = if (control.kind == "stick" || control.kind == "dpad") 118.dp else if (control.gt) 92.dp else 62.dp
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (control.ux * parentW - with(density) { size.toPx() } / 2).roundToInt(),
                            (control.uy * parentH - with(density) { size.toPx() } / 2).roundToInt()
                        )
                    }
                    .size(size)
            ) {
                when (control.kind) {
                    "stick" -> StickWidget(control.label, opacity, editing, parentW, parentH, control.id, onStick, onMove)
                    "dpad" -> DpadWidget(opacity, editing, parentW, parentH, control, onButton, onMove)
                    else -> ButtonWidget(control, opacity, editing, parentW, parentH, onButton, onMove)
                }
            }
        }
    }
}

@Composable
private fun StickWidget(
    label: String,
    opacity: Float,
    editing: Boolean,
    parentW: Float,
    parentH: Float,
    id: String,
    onStick: (id: String, x: Float, y: Float, active: Boolean) -> Unit,
    onMove: (id: String, dx: Float, dy: Float) -> Unit
) {
    var knob by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f * opacity))
            .border(1.5.dp, Cyan.copy(alpha = opacity), CircleShape)
            .pointerInput(editing) {
                if (editing) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onMove(id, drag.x / parentW, drag.y / parentH)
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { knob = androidx.compose.ui.geometry.Offset.Zero },
                        onDragEnd = {
                            knob = androidx.compose.ui.geometry.Offset.Zero
                            onStick(id, 0f, 0f, false)
                        },
                        onDragCancel = {
                            knob = androidx.compose.ui.geometry.Offset.Zero
                            onStick(id, 0f, 0f, false)
                        }
                    ) { change, drag ->
                        change.consume()
                        val max = minOf(size.width, size.height) / 2f * 0.62f
                        knob = androidx.compose.ui.geometry.Offset(
                            (knob.x + drag.x).coerceIn(-max, max),
                            (knob.y + drag.y).coerceIn(-max, max)
                        )
                        onStick(id, knob.x / max, knob.y / max, true)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(46.dp)
                .clip(CircleShape)
                .background(Cyan.copy(alpha = 0.85f * opacity)),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Ink, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ButtonWidget(
    control: Control,
    opacity: Float,
    editing: Boolean,
    parentW: Float,
    parentH: Float,
    onButton: (id: String, down: Boolean) -> Unit,
    onMove: (id: String, dx: Float, dy: Float) -> Unit
) {
    val color = if (control.gt) Gold else Amber
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(if (control.gt) 18.dp else 999.dp))
            .background(color.copy(alpha = 0.18f * opacity))
            .border(1.5.dp, color.copy(alpha = opacity), RoundedCornerShape(if (control.gt) 18.dp else 999.dp))
            .pointerInput(editing, control.id) {
                if (editing) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onMove(control.id, drag.x / parentW, drag.y / parentH)
                    }
                } else {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.any { it.pressed }
                            val was = event.changes.any { it.previousPressed }
                            if (pressed && !was) onButton(control.id, true)
                            if (!pressed && was) onButton(control.id, false)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(control.label, color = color, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
private fun DpadWidget(
    opacity: Float,
    editing: Boolean,
    parentW: Float,
    parentH: Float,
    control: Control,
    onButton: (id: String, down: Boolean) -> Unit,
    onMove: (id: String, dx: Float, dy: Float) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(editing) {
                if (editing) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onMove(control.id, drag.x / parentW, drag.y / parentH)
                    }
                }
            }
    ) {
        listOf("du" to Alignment.TopCenter, "dd" to Alignment.BottomCenter, "dl" to Alignment.CenterStart, "dr" to Alignment.CenterEnd)
            .forEach { (id, align) ->
                Box(
                    Modifier
                        .align(align)
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(0.12f * opacity))
                        .pointerInput(id, editing) {
                            if (editing) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.any { it.pressed }
                                    val was = event.changes.any { it.previousPressed }
                                    if (pressed && !was) onButton(id, true)
                                    if (!pressed && was) onButton(id, false)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(id.drop(1).uppercase(), color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardSheet(
    modifier: Modifier,
    onKey: (code: Int, down: Boolean) -> Unit,
    onText: (String) -> Unit,
    onClose: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(InkRaised)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keyboard", color = Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip("Tutup", onClose)
        }
        Spacer(Modifier.height(8.dp))
        val rows = listOf(
            listOf("1" to 8, "2" to 9, "3" to 10, "4" to 11, "5" to 12, "6" to 13, "7" to 14, "8" to 15, "9" to 16, "0" to 7),
            listOf("Q" to 45, "W" to 51, "E" to 33, "R" to 46, "T" to 48, "Y" to 53, "U" to 49, "I" to 37, "O" to 43, "P" to 44),
            listOf("A" to 29, "S" to 47, "D" to 32, "F" to 34, "G" to 35, "H" to 36, "J" to 38, "K" to 39, "L" to 40),
            listOf("Z" to 54, "X" to 52, "C" to 31, "V" to 50, "B" to 30, "N" to 42, "M" to 41)
        )
        rows.forEach { row ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (label, code) ->
                    KeyCap(label) { down -> onKey(code, down) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyCap("Spasi", wide = true) { onKey(62, it) }
            KeyCap("⌫") { onKey(67, it) }
            KeyCap("⏎") { onKey(66, it) }
            KeyCap("Esc") { onKey(111, it) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ketik lalu kirim") },
                singleLine = true,
                colors = fieldColors()
            )
            Spacer(Modifier.width(8.dp))
            Chip("Kirim") {
                if (draft.isNotEmpty()) {
                    onText(draft)
                    draft = ""
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, wide: Boolean = false, onPress: (Boolean) -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .width(if (wide) 140.dp else 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.08f))
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.any { it.pressed }
                        val was = event.changes.any { it.previousPressed }
                        if (pressed && !was) onPress(true)
                        if (!pressed && was) onPress(false)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Paper,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(0.16f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber,
    unfocusedBorderColor = Color.White.copy(0.16f),
    focusedTextColor = Paper,
    unfocusedTextColor = Paper,
    cursorColor = Amber,
    focusedLabelColor = Amber,
    unfocusedLabelColor = Muted
)

private fun gamepadButton(code: Int): String? = when (code) {
    KeyEvent.KEYCODE_BUTTON_A -> "a"
    KeyEvent.KEYCODE_BUTTON_B -> "b"
    KeyEvent.KEYCODE_BUTTON_X -> "x"
    KeyEvent.KEYCODE_BUTTON_Y -> "y"
    KeyEvent.KEYCODE_BUTTON_L1 -> "lb"
    KeyEvent.KEYCODE_BUTTON_R1 -> "rb"
    KeyEvent.KEYCODE_BUTTON_L2 -> "lt"
    KeyEvent.KEYCODE_BUTTON_R2 -> "rt"
    KeyEvent.KEYCODE_BUTTON_THUMBL -> "l3"
    KeyEvent.KEYCODE_BUTTON_THUMBR -> "r3"
    KeyEvent.KEYCODE_BUTTON_START -> "start"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "select"
    KeyEvent.KEYCODE_DPAD_UP -> "du"
    KeyEvent.KEYCODE_DPAD_DOWN -> "dd"
    KeyEvent.KEYCODE_DPAD_LEFT -> "dl"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "dr"
    else -> null
}
