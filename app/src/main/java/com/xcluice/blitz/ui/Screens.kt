package com.xcluice.blitz.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcluice.blitz.engine.FileMeta
import com.xcluice.blitz.engine.Item
import com.xcluice.blitz.engine.Phase
import com.xcluice.blitz.engine.Request
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xcluice.blitz.engine.Role
import com.xcluice.blitz.engine.UiState
import java.util.Locale

fun fmtBytes(b: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1000 && i < units.size - 1) {
        v /= 1000
        i++
    }
    return if (i == 0) "$b B"
    else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun fmtMbps(bps: Double): String = String.format(Locale.US, "%.1f", bps / 1e6)

fun fmtTime(s: Long): String = when {
    s < 0 -> "--"
    s < 60 -> "${s}s"
    else -> "${s / 60}m ${s % 60}s"
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(
    myName: String,
    myAvatar: Int,
    updateText: String?,
    updateBusy: Boolean,
    onUpdate: () -> Unit,
    onProfile: () -> Unit,
    onHistory: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth().popIn(0), verticalAlignment = Alignment.CenterVertically) {
            BoltIcon(Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Blitz", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Pal.Ink)
            Spacer(Modifier.weight(1f))
            Box(Modifier.bounceClick(onClick = onHistory)) { Chip("History") }
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .bounceClick(onClick = onProfile)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Pal.Fill)
                    .border(1.dp, Pal.Line, RoundedCornerShape(22.dp))
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(myAvatar, Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Text(myName, color = Pal.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
        if (updateText != null) {
            Glass(Modifier.fillMaxWidth().padding(top = 12.dp).popIn(30), radius = 22.dp, strong = true) {
                Row(
                    Modifier.padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(updateText, color = Pal.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (updateBusy) Spinner(Modifier.size(28.dp).padding(end = 8.dp))
                    else PrimaryButton("Update", onClick = onUpdate)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().popIn(60), contentAlignment = Alignment.Center) {
            PulseRings(Modifier.size(300.dp), startFrac = 0.28f, spread = 1f)
            Glass(Modifier.size(104.dp), radius = 52.dp, strong = true) {
                FloatingBolt(Modifier.align(Alignment.Center).size(44.dp))
            }
        }
        Text(
            text = "Phone to phone,\nstraight line.",
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            color = Pal.Ink,
            modifier = Modifier.popIn(120)
        )
        Text(
            text = "No internet. No cables. Just speed.",
            color = Pal.Ink2,
            fontSize = 15.sp,
            modifier = Modifier.popIn(160).padding(top = 8.dp, bottom = 26.dp)
        )
        Row(
            Modifier.fillMaxWidth().popIn(200),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeButton("Send", true, Modifier.weight(1f), onSend)
            HomeButton("Receive", false, Modifier.weight(1f), onReceive)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.popIn(260), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("5 GHz")
            Chip("4 streams")
            Chip("AES-GCM")
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Chip(text: String) {
    Glass(radius = 14.dp) {
        Text(
            text = text,
            color = Pal.Ink2,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun HomeButton(title: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val fg = if (filled) Pal.Bg else Pal.Ink
    Column(
        modifier
            .bounceClick(onClick = onClick)
            .clip(shape)
            .background(if (filled) Pal.Ink else Pal.Fill)
            .border(1.dp, if (filled) Pal.Ink else Pal.Line, shape)
            .padding(20.dp)
    ) {
        ArrowIcon(up = filled, modifier = Modifier.size(24.dp), color = fg)
        Spacer(Modifier.height(26.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

// ---------------------------------------------------------------- send picker

@Composable
fun SendScreen(
    fileMetas: List<FileMeta>,
    summary: String,
    canSend: Boolean,
    tab: Int,
    onTab: (Int) -> Unit,
    apps: List<AppEntry>,
    appsLoading: Boolean,
    selectedApps: Set<String>,
    onToggleApp: (String) -> Unit,
    encrypt: Boolean,
    onEncrypt: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onClear: () -> Unit,
    onFind: () -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = "\u2039 Back",
            color = Pal.Ink2,
            fontSize = 16.sp,
            modifier = Modifier
                .bounceClick(onClick = onBack)
                .padding(vertical = 8.dp)
        )
        Text("Send", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Text(
            text = summary,
            color = Pal.Ink2,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        Segmented(
            options = listOf("Files", "Apps"),
            selected = tab,
            onSelect = onTab,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Glass(Modifier.fillMaxWidth().weight(1f)) {
            if (tab == 1) {
                AppsPane(
                    apps = apps,
                    loading = appsLoading,
                    selected = selectedApps,
                    onToggle = onToggleApp
                )
            } else if (fileMetas.isEmpty()) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FloatingBolt(Modifier.size(34.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Nothing picked yet.\nPhotos, videos, APKs, anything.",
                        color = Pal.Ink2,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Choose files", onClick = onAdd)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
                    itemsIndexed(fileMetas, key = { i, m -> "$i-${m.name}" }) { i, m ->
                        Box(Modifier.animateItem().swipeToRemove { onRemoveFile(i) }) {
                            FileRow(m.name, m.size)
                        }
                    }
                }
            }
        }
        if (tab == 0 && fileMetas.isNotEmpty()) {
            Text(
                text = "Swipe a file sideways to remove it",
                color = Pal.Ink3,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally)
            )
        }
        Spacer(Modifier.height(10.dp))
        if (canSend || (tab == 0 && fileMetas.isNotEmpty())) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0) {
                    GhostButton("Add more", Modifier.weight(1f), onClick = onAdd)
                }
                GhostButton("Clear all", Modifier.weight(1f), onClick = onClear)
            }
            Spacer(Modifier.height(10.dp))
        }
        Glass(Modifier.fillMaxWidth(), radius = 22.dp) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Encrypt stream", color = Pal.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (encrypt) "AES-GCM on top of the Wi-Fi link"
                        else "Turbo: the Wi-Fi Direct link is already WPA2-encrypted",
                        color = Pal.Ink2,
                        fontSize = 12.sp
                    )
                }
                GlassSwitch(on = encrypt, onChange = onEncrypt)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = "Find nearby phone",
            modifier = Modifier.fillMaxWidth(),
            enabled = canSend,
            onClick = onFind
        )
        Text(
            text = "or scan a QR code",
            color = Pal.Ink3,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .bounceClick(enabled = canSend, onClick = onScan)
                .padding(top = 10.dp, bottom = 2.dp)
        )
    }
}

@Composable
private fun FileRow(name: String, size: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            color = Pal.Ink,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(fmtBytes(size), color = Pal.Ink2, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- run / progress

private fun tick(view: android.view.View, strong: Boolean) {
    val c = if (strong && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.CLOCK_TICK
    view.performHapticFeedback(c)
}

@Composable
fun RunScreen(
    st: UiState,
    myName: String,
    myAvatar: Int,
    request: Request?,
    hostError: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (List<Item>) -> Unit
) {
    val sending = st.role == Role.SEND
    val view = LocalView.current
    LaunchedEffect(st.phase) {
        if (st.phase == Phase.TRANSFERRING) tick(view, false)
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val title = when (st.phase) {
            Phase.DONE -> if (sending) "Sent" else "Received"
            Phase.ERROR -> "Hit a snag"
            else -> if (sending) "Sending" else "Receiving"
        }
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        if (st.phase != Phase.ERROR && st.status.isNotEmpty()) {
            Text(
                text = st.status,
                color = Pal.Ink2,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(18.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            when (st.phase) {
                Phase.PREPARING, Phase.CONNECTING -> {
                    PhoneLink(
                        Modifier.align(Alignment.Center).fillMaxWidth().height(150.dp),
                        active = false, flow = 0f, youAreSender = sending
                    )
                }
                Phase.WAITING -> WaitingPanel(st.qr, myName, myAvatar, request, hostError, onAccept, onDecline)
                Phase.TRANSFERRING -> TransferPanel(st, sending)
                Phase.DONE -> DonePanel(st, sending, onOpen)
                Phase.ERROR -> {
                    Glass(Modifier.fillMaxWidth().align(Alignment.Center), radius = 24.dp) {
                        Text(
                            text = st.error ?: "Something went wrong.",
                            color = Pal.Ink,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(22.dp)
                        )
                    }
                }
                Phase.IDLE -> {}
            }
        }

        Spacer(Modifier.height(12.dp))
        when (st.phase) {
            Phase.DONE, Phase.ERROR -> PrimaryButton(
                text = if (st.phase == Phase.DONE) "Done" else "Back",
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel
            )
            else -> GhostButton("Cancel", Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}

@Composable
private fun WaitingPanel(
    qr: String?,
    myName: String,
    myAvatar: Int,
    request: Request?,
    hostError: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var showQr by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(request) {
        if (request != null) tick(view, false)
    }
    Box(Modifier.fillMaxSize()) {
        if (request != null) {
            Glass(Modifier.align(Alignment.Center).fillMaxWidth().popIn(0), radius = 30.dp, strong = true) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(request.avatar, Modifier.size(88.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(request.name, color = Pal.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val what = if (request.count == 1 && request.first.isNotEmpty()) request.first
                    else "${request.count} files"
                    Text(
                        text = "wants to send you $what",
                        color = Pal.Ink2,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(fmtBytes(request.total), color = Pal.Ink3, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("Decline", Modifier.weight(1f), onClick = onDecline)
                        PrimaryButton("Accept", Modifier.weight(1f), onClick = onAccept)
                    }
                }
            }
        } else if (showQr && qr != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCard(qr)
                Text(
                    text = "Back to nearby",
                    color = Pal.Ink2,
                    fontSize = 14.sp,
                    modifier = Modifier.bounceClick { showQr = false }.padding(12.dp)
                )
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    PulseRings(Modifier.size(320.dp), startFrac = 0.3f, spread = 1f)
                    Avatar(myAvatar, Modifier.size(112.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("Visible as $myName", color = Pal.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = hostError ?: "Open Blitz on the other phone, tap Send and pick you.",
                    color = Pal.Ink2,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp)
                )
                if (qr != null) {
                    Text(
                        text = "Show a QR code instead",
                        color = Pal.Ink3,
                        fontSize = 13.sp,
                        modifier = Modifier.bounceClick { showQr = true }.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCard(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.popIn(0)) {
        Box(contentAlignment = Alignment.Center) {
            PulseRings(Modifier.size(380.dp), startFrac = 0.62f, spread = 1f)
            Glass(radius = 30.dp) {
                Box(Modifier.padding(18.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .padding(14.dp)
                    ) {
                        QrImage(text, Modifier.size(230.dp))
                    }
                }
            }
        }
        Text(
            text = "Open Blitz on the other phone, tap Send, pick files and scan this.",
            color = Pal.Ink2,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp)
        )
        Text(
            text = "Keep both phones close. Wi-Fi and Location must be on.",
            color = Pal.Ink3,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun TransferPanel(st: UiState, sending: Boolean) {
    val progress = if (st.total > 0) st.done.toFloat() / st.total.toFloat() else 0f
    val intensity = if (st.peak > 0.0) (st.speed / st.peak).toFloat().coerceIn(0f, 1f) else 0f
    // Files go roughly in order, so spread the byte count over them to get a ring per file.
    val rows = remember(st.items, st.done) {
        var before = 0L
        st.items.map { item ->
            val p = if (item.size <= 0L) 1f
            else ((st.done - before).toFloat() / item.size.toFloat()).coerceIn(0f, 1f)
            before += item.size
            Pair(item, p)
        }
    }
    val finished = rows.count { it.second >= 1f }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PhoneLink(
            Modifier.fillMaxWidth().height(70.dp),
            active = true, flow = intensity, youAreSender = sending
        )
        Spacer(Modifier.height(10.dp))
        LiquidGauge(progress = progress, intensity = intensity, modifier = Modifier.size(220.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RollingText(fmtMbps(st.speed), 48.sp, FontWeight.Bold, Pal.Ink)
                Text("MB/s", fontSize = 14.sp, color = Pal.Ink2)
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = Pal.Ink2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Moved", "${fmtBytes(st.done)} / ${fmtBytes(st.total)}", Modifier.weight(1.5f))
            Tile("Left", fmtTime(st.eta), Modifier.weight(1f))
            Tile("Peak", fmtMbps(st.peak) + " MB/s", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Glass(Modifier.fillMaxWidth().weight(1f, fill = false), radius = 22.dp) {
            LazyColumn(Modifier.padding(8.dp)) {
                itemsIndexed(rows, key = { i, r -> "$i-${r.first.name}" }) { _, r ->
                    if (r.second < 1f) {
                        Row(
                            Modifier
                                .animateItem()
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RingProgress(r.second, Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = r.first.name,
                                color = Pal.Ink,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(fmtBytes(r.first.size), color = Pal.Ink2, fontSize = 12.sp)
                        }
                    }
                }
                if (finished > 0) {
                    item(key = "finished") {
                        Text(
                            text = "$finished done",
                            color = Pal.Ink3,
                            fontSize = 12.sp,
                            modifier = Modifier.animateItem().padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Glass(modifier, radius = 18.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, color = Pal.Ink3, fontSize = 11.sp)
            Text(
                text = value,
                color = Pal.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DonePanel(st: UiState, sending: Boolean, onOpen: (List<Item>) -> Unit) {
    val pop = remember { Animatable(0f) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        tick(view, true)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 240f))
    }
    val secs = (st.elapsedMs / 1000.0).coerceAtLeast(0.001)
    val avg = st.total / secs
    val rows = remember(st.items, sending) {
        if (sending) st.items.map { ReceivedRow(it, listOf(it)) } else groupReceived(st.items)
    }
    Box(Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(92.dp)
                        .graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        }
                        .clip(CircleShape)
                        .background(Pal.Ink),
                    contentAlignment = Alignment.Center
                ) {
                    CheckIcon(Modifier.size(52.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Size", fmtBytes(st.total), Modifier.weight(1f))
                Tile("Average", fmtMbps(avg) + " MB/s", Modifier.weight(1.2f))
                Tile("Time", fmtTime((st.elapsedMs / 1000)), Modifier.weight(0.8f))
            }
            Spacer(Modifier.height(14.dp))
            Glass(Modifier.fillMaxWidth().weight(1f, fill = false), radius = 22.dp) {
                LazyColumn(Modifier.padding(8.dp)) {
                    items(rows) { row ->
                        val item = row.item
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    color = Pal.Ink,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val extra = if (row.parts.size > 1) "  \u00b7  base + ${row.parts.size - 1} splits" else ""
                                Text(
                                    text = fmtBytes(row.parts.sumOf { p -> p.size }) + extra,
                                    color = Pal.Ink2,
                                    fontSize = 12.sp
                                )
                            }
                            if (!sending && item.uri != null) {
                                Spacer(Modifier.width(8.dp))
                                GhostButton(
                                    text = if (item.mime == APK_MIME) "Install" else "Open",
                                    onClick = { onOpen(row.parts) }
                                )
                            }
                        }
                    }
                }
            }
            if (!sending) {
                Text(
                    text = "Saved in Downloads/Xcluice Blitz",
                    color = Pal.Ink3,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
        Confetti(Modifier.fillMaxWidth().height(220.dp))
    }
}
