package com.xcluice.blitz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcluice.blitz.HistoryRec
import com.xcluice.blitz.engine.Peer
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------- profile

@Composable
fun ProfileScreen(
    name: String,
    avatar: Int,
    canBack: Boolean,
    onName: (String) -> Unit,
    onAvatar: (Int) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (canBack) {
            Text(
                text = "‹ Back",
                color = Pal.Ink2,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.Start)
                    .bounceClick(onClick = onBack)
                    .padding(vertical = 8.dp)
            )
        } else {
            Spacer(Modifier.height(24.dp))
        }
        Text("Your profile", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Text(
            text = "This is how nearby phones see you.",
            color = Pal.Ink2,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )
        Box(contentAlignment = Alignment.Center) {
            PulseRings(Modifier.size(190.dp), startFrac = 0.5f, spread = 1f)
            Avatar(avatar, Modifier.size(104.dp))
        }
        Spacer(Modifier.height(14.dp))
        Glass(Modifier.fillMaxWidth(), radius = 20.dp) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                BasicTextField(
                    value = name,
                    onValueChange = { onName(it.take(14)) },
                    singleLine = true,
                    textStyle = TextStyle(color = Pal.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Pal.Ink),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (name.isEmpty()) Text("Your name", color = Pal.Ink3, fontSize = 18.sp)
                            inner()
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items((0 until AVATAR_COUNT).toList()) { i ->
                AvatarCell(i, i == avatar) { onAvatar(i) }
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = "Save",
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank(),
            onClick = onSave
        )
    }
}

@Composable
private fun AvatarCell(id: Int, on: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (on) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 450f),
        label = "av"
    )
    val ring by animateFloatAsState(if (on) 1f else 0f, label = "avring")
    Box(
        Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Avatar(
            id,
            Modifier
                .size(68.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .border(2.dp, Color.White.copy(alpha = ring), CircleShape)
        )
    }
}

// ---------------------------------------------------------------- discover

@Composable
fun DiscoverScreen(
    myAvatar: Int,
    peers: List<Peer>,
    asking: Peer?,
    message: String?,
    onPick: (Peer) -> Unit,
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
        Text("Nearby", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Text(
            text = "Ask the other phone to open Receive.",
            color = Pal.Ink2,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (asking != null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(asking.avatar, Modifier.size(96.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(asking.name, color = Pal.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Waiting for them to accept…",
                        color = Pal.Ink2,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                    )
                    Spinner(Modifier.size(44.dp))
                }
            } else if (peers.isEmpty()) {
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    PulseRings(Modifier.size(320.dp), startFrac = 0.2f, spread = 1f)
                    Avatar(myAvatar, Modifier.size(84.dp))
                }
                Text(
                    text = message ?: "Looking for phones…",
                    color = Pal.Ink2,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(peers, key = { it.address }) { p ->
                        Glass(
                            Modifier
                                .fillMaxWidth()
                                .popIn(0)
                                .bounceClick { onPick(p) },
                            radius = 26.dp
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p.avatar, Modifier.size(56.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.name,
                                        color = Pal.Ink,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("Tap to send", color = Pal.Ink2, fontSize = 13.sp)
                                }
                                ArrowIcon(up = true, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        if (message != null && peers.isNotEmpty() && asking == null) {
            Text(message, color = Pal.Ink2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("Use a QR code instead", Modifier.fillMaxWidth(), onClick = onScan)
    }
}

// ---------------------------------------------------------------- history

@Composable
fun HistoryScreen(
    records: List<HistoryRec>,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
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
        Text("History", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (records.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FloatingBolt(Modifier.size(34.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("No transfers yet.", color = Pal.Ink2, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(records) { r ->
                        Glass(Modifier.fillMaxWidth(), radius = 22.dp) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(r.avatar, Modifier.size(48.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = (if (r.sending) "Sent to " else "Received from ") + r.peer,
                                        color = Pal.Ink,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val files = if (r.count == 1 && r.first.isNotEmpty()) r.first else "${r.count} files"
                                    Text(
                                        text = files + "  ·  " + fmtBytes(r.bytes),
                                        color = Pal.Ink2,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = fmtMbps(r.avgBps) + " MB/s avg  ·  " + fmt.format(Date(r.time)),
                                        color = Pal.Ink3,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (records.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GhostButton("Clear history", Modifier.fillMaxWidth(), onClick = onClear)
        }
    }
}

// ---------------------------------------------------------------- internet

@Composable
fun NetScreen(
    onSend: () -> Unit,
    onReceive: (String) -> Unit,
    onBack: () -> Unit
) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val code = com.xcluice.blitz.engine.NetCode.normalize(text)
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
        Text("Internet", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Text(
            text = "Works anywhere. Files are encrypted on your phone and only the other phone can open them.",
            color = Pal.Ink2,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .popIn(0)
                .bounceClick(onClick = onSend)
                .clip(RoundedCornerShape(28.dp))
                .background(Pal.Ink)
                .padding(22.dp)
        ) {
            ArrowIcon(up = true, modifier = Modifier.size(24.dp), color = Pal.Bg)
            Spacer(Modifier.height(22.dp))
            Text("Send files", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Bg)
            Text("Pick files, get a code, share it.", fontSize = 13.sp, color = Pal.Bg.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(14.dp))
        Glass(Modifier.fillMaxWidth().popIn(80), radius = 28.dp) {
            Column(Modifier.padding(22.dp)) {
                ArrowIcon(up = false, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(18.dp))
                Text("Receive", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Ink)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Pal.Fill)
                        .border(1.dp, Pal.Line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.uppercase().take(14) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Pal.Ink,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(Pal.Ink),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (text.isEmpty()) {
                                    Text("XXXX-XXXX-XXXX", color = Pal.Ink3, fontSize = 22.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Receive",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = code != null,
                    onClick = { if (code != null) onReceive(code) }
                )
            }
        }
    }
}
