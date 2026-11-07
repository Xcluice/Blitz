package com.xcluice.blitz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.popIn(0), verticalAlignment = Alignment.CenterVertically) {
            BoltIcon(Modifier.size(38.dp))
            Spacer(Modifier.width(10.dp))
            Text("Blitz", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        }
        Text(
            text = "Phone to phone in a straight line. No internet, no cables.",
            color = Pal.Ink2,
            fontSize = 15.sp,
            modifier = Modifier
                .popIn(60)
                .padding(top = 8.dp, bottom = 30.dp)
        )
        BigAction("Send", "Pick files, scan the receiver's code", true, 120, onSend)
        Spacer(Modifier.height(14.dp))
        BigAction("Receive", "Show a code. Files land in Downloads.", false, 200, onReceive)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.popIn(280), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("5 GHz link")
            Chip("4 streams")
            Chip("AES-GCM")
        }
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
private fun BigAction(title: String, sub: String, send: Boolean, delayMs: Int, onClick: () -> Unit) {
    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .popIn(delayMs)
            .bounceClick(onClick = onClick),
        radius = 30.dp
    ) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (send) Pal.Mint else Pal.Coral),
                contentAlignment = Alignment.Center
            ) {
                ArrowIcon(up = send, modifier = Modifier.size(26.dp), color = Pal.Bg)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Ink)
                Text(sub, fontSize = 13.sp, color = Pal.Ink2, modifier = Modifier.padding(top = 2.dp))
            }
        }
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
    onClear: () -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = "‹ Back",
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
                    items(fileMetas) { m -> FileRow(m.name, m.size) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
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
            text = "Scan the receiver's code",
            modifier = Modifier.fillMaxWidth(),
            enabled = canSend,
            onClick = onScan
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

@Composable
fun RunScreen(st: UiState, onCancel: () -> Unit, onOpen: (Item) -> Unit) {
    val sending = st.role == Role.SEND
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
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spinner(Modifier.size(84.dp))
                    }
                }
                Phase.WAITING -> {
                    val qr = st.qr
                    if (qr != null) QrCard(qr)
                }
                Phase.TRANSFERRING -> TransferPanel(st)
                Phase.DONE -> DonePanel(st, sending, onOpen)
                Phase.ERROR -> {
                    Glass(Modifier.fillMaxWidth().align(Alignment.Center), radius = 24.dp) {
                        Text(
                            text = st.error ?: "Something went wrong.",
                            color = Pal.Err,
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
private fun QrCard(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.popIn(0)) {
        Glass(radius = 30.dp) {
            Box(Modifier.padding(18.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(14.dp)
                ) {
                    QrImage(text, Modifier.size(250.dp))
                }
            }
        }
        Text(
            text = "Open Blitz on the other phone, tap Send, pick files and scan this.",
            color = Pal.Ink2,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp, start = 12.dp, end = 12.dp)
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
private fun TransferPanel(st: UiState) {
    val progress = if (st.total > 0) st.done.toFloat() / st.total.toFloat() else 0f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LiquidGauge(progress = progress, modifier = Modifier.size(250.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = fmtMbps(st.speed),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    color = Pal.Ink
                )
                Text("MB/s", fontSize = 15.sp, color = Pal.Ink2)
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = Pal.Ink2,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Moved", "${fmtBytes(st.done)} / ${fmtBytes(st.total)}", Modifier.weight(1.5f))
            Tile("Left", fmtTime(st.eta), Modifier.weight(1f))
            Tile("Peak", fmtMbps(st.peak) + " MB/s", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Glass(Modifier.fillMaxWidth().weight(1f, fill = false), radius = 22.dp) {
            LazyColumn(Modifier.padding(8.dp)) {
                items(st.items) { it2 -> FileRow(it2.name, it2.size) }
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
private fun DonePanel(st: UiState, sending: Boolean, onOpen: (Item) -> Unit) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
    val secs = (st.elapsedMs / 1000.0).coerceAtLeast(0.001)
    val avg = st.total / secs
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(92.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .clip(CircleShape)
                .background(Pal.Mint),
            contentAlignment = Alignment.Center
        ) {
            CheckIcon(Modifier.size(52.dp))
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
                items(st.items) { item ->
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
                            Text(fmtBytes(item.size), color = Pal.Ink2, fontSize = 12.sp)
                        }
                        if (!sending && item.uri != null) {
                            Spacer(Modifier.width(8.dp))
                            GhostButton(
                                text = if (item.mime == APK_MIME) "Install" else "Open",
                                onClick = { onOpen(item) }
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
}
