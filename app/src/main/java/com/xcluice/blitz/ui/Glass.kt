package com.xcluice.blitz.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

object Pal {
    val Bg = Color(0xFF0D0F14)
    val Ink = Color(0xFFF2F4F7)
    val Ink2 = Color(0x99F2F4F7)
    val Ink3 = Color(0x59F2F4F7)
    val Mint = Color(0xFF8BD5C4)
    val Teal = Color(0xFF2A8C82)
    val Coral = Color(0xFFE07A5F)
    val Err = Color(0xFFE5877A)
    val BlobTeal = Color(0x802A8C82)
    val BlobCoral = Color(0x66C9573F)
    val BlobIndigo = Color(0x804A4DB5)
}

/** Press feedback without any ripple or tap highlight: a springy squash. */
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 450f),
        label = "bounce"
    )
    val latest by rememberUpdatedState(onClick)
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.4f
        }
        .pointerInput(enabled) {
            detectTapGestures(
                onPress = {
                    if (enabled) {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    }
                },
                onTap = { if (enabled) latest() }
            )
        }
}

/** Springs in from below on first composition. */
fun Modifier.popIn(delayMs: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 170f))
    }
    this.graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * 70f
    }
}

@Composable
fun GlassBg(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
        label = "a"
    )
    val b by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse),
        label = "b"
    )
    val c by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Reverse),
        label = "c"
    )
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Canvas(Modifier.fillMaxSize().blur(70.dp)) {
            val w = size.width
            val h = size.height
            drawCircle(Pal.BlobTeal, radius = w * 0.55f, center = Offset(w * (0.15f + 0.5f * a), h * (0.12f + 0.2f * b)))
            drawCircle(Pal.BlobCoral, radius = w * 0.5f, center = Offset(w * (0.9f - 0.6f * b), h * (0.55f + 0.25f * c)))
            drawCircle(Pal.BlobIndigo, radius = w * 0.6f, center = Offset(w * (0.2f + 0.4f * c), h * (0.95f - 0.2f * a)))
        }
        content()
    }
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .drawBehind {
                val inset = radius.toPx() * 0.9f
                drawLine(
                    color = Color.White.copy(alpha = 0.28f),
                    start = Offset(inset, 1.dp.toPx()),
                    end = Offset(size.width - inset, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            },
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .bounceClick(enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .background(Pal.Mint)
            .padding(horizontal = 24.dp, vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Pal.Bg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .bounceClick(enabled = enabled, onClick = onClick)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Pal.Ink, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

@Composable
fun GlassSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(
        targetValue = if (on) 24.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "sw"
    )
    val track by animateColorAsState(
        targetValue = if (on) Pal.Mint else Color.White.copy(alpha = 0.18f),
        animationSpec = tween(200),
        label = "swc"
    )
    Box(
        Modifier
            .bounceClick { onChange(!on) }
            .size(54.dp, 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(track)
    ) {
        Box(
            Modifier
                .offset(x = x, y = 3.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun BoltIcon(modifier: Modifier = Modifier, color: Color = Pal.Mint) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, h * 0.04f)
            lineTo(w * 0.20f, h * 0.56f)
            lineTo(w * 0.47f, h * 0.56f)
            lineTo(w * 0.38f, h * 0.96f)
            lineTo(w * 0.82f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun ArrowIcon(up: Boolean, modifier: Modifier = Modifier, color: Color = Pal.Ink) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        val tip = if (up) h * 0.1f else h * 0.9f
        val tail = if (up) h * 0.9f else h * 0.1f
        val wing = tip + (if (up) 1f else -1f) * h * 0.28f
        drawLine(color, Offset(w / 2, tail), Offset(w / 2, tip), sw, StrokeCap.Round)
        drawLine(color, Offset(w / 2, tip), Offset(w * 0.2f, wing), sw, StrokeCap.Round)
        drawLine(color, Offset(w / 2, tip), Offset(w * 0.8f, wing), sw, StrokeCap.Round)
    }
}

@Composable
fun CheckIcon(modifier: Modifier = Modifier, color: Color = Pal.Bg) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawLine(color, Offset(w * 0.22f, h * 0.54f), Offset(w * 0.43f, h * 0.74f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.43f, h * 0.74f), Offset(w * 0.80f, h * 0.30f), sw, StrokeCap.Round)
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle"
    )
    Canvas(modifier) {
        val sw = 6.dp.toPx()
        val d = size.minDimension - sw
        drawCircle(Color.White.copy(alpha = 0.10f), radius = d / 2f, style = Stroke(sw))
        drawArc(
            color = Pal.Mint,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(d, d),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
    }
}

/** A glass disc that fills with two drifting waves as the transfer progresses. */
@Composable
fun LiquidGauge(progress: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "level")
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, (2.0 * Math.PI).toFloat(),
        infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase"
    )
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val level = h * (1f - p)
            val twoPi = (2.0 * Math.PI).toFloat()
            fun wave(shift: Float, amp: Float, color: Color) {
                val path = Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w) {
                    val y = level + amp * kotlin.math.sin(x / w * twoPi * 1.4f + phase + shift)
                    path.lineTo(x, y)
                    x += 6f
                }
                path.lineTo(w, h)
                path.close()
                drawPath(path, color)
            }
            wave(0f, 8.dp.toPx(), Pal.Mint.copy(alpha = 0.55f))
            wave(2.2f, 6.dp.toPx(), Pal.Teal.copy(alpha = 0.5f))
        }
        content()
    }
}

fun makeQr(text: String): Bitmap {
    val hints = HashMap<EncodeHintType, Any>()
    hints[EncodeHintType.MARGIN] = 0
    hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640, hints)
    val w = m.width
    val h = m.height
    val dark = 0xFF0D0F14.toInt()
    val light = 0xFFFFFFFF.toInt()
    val px = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            px[y * w + x] = if (m.get(x, y)) dark else light
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { makeQr(text) }
    Image(bitmap = bmp.asImageBitmap(), contentDescription = "Pairing code", modifier = modifier)
}
