package com.xcluice.blitz.ui

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Black and white only. Everything else is a shade of white at some opacity. */
object Pal {
    val Bg = Color(0xFF000000)
    val Ink = Color(0xFFFFFFFF)
    val Ink2 = Color(0xA6FFFFFF)
    val Ink3 = Color(0x61FFFFFF)
    val Line = Color(0x33FFFFFF)
    val Fill = Color(0x14FFFFFF)
    val FillStrong = Color(0x24FFFFFF)
}

/** -1..1 sideways tilt of the phone; moves the glass highlights. */
val LocalTilt = compositionLocalOf<State<Float>> { mutableStateOf(0f) }

@Composable
fun rememberTilt(enabled: Boolean): State<Float> {
    val ctx = LocalContext.current
    val tilt = remember { mutableStateOf(0f) }
    DisposableEffect(enabled) {
        if (!enabled) {
            tilt.value = 0f
            return@DisposableEffect onDispose { }
        }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val target = (-e.values[0] / 6f).coerceIn(-1f, 1f)
                tilt.value = tilt.value + (target - tilt.value) * 0.12f
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

// ------------------------------------------------------------------ modifiers

/** Press feedback without ripple or tap highlight: a springy squash plus a light tick. */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    haptic: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val view = LocalView.current
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
                onTap = {
                    if (enabled) {
                        if (haptic) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        latest()
                    }
                }
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

/** Drag sideways past a third of the width to dismiss. */
fun Modifier.swipeToRemove(onRemove: () -> Unit): Modifier = composed {
    val x = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onRemove)
    this
        .offset { IntOffset(x.value.roundToInt(), 0) }
        .graphicsLayer { alpha = 1f - (abs(x.value) / 700f).coerceIn(0f, 0.9f) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        val w = size.width.toFloat()
                        if (abs(x.value) > w * 0.35f) {
                            x.animateTo(if (x.value > 0f) w else -w, tween(160))
                            latest()
                        } else {
                            x.animateTo(0f, spring())
                        }
                    }
                },
                onDragCancel = { scope.launch { x.animateTo(0f, spring()) } },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch { x.snapTo(x.value + dragAmount) }
                }
            )
        }
}

// ------------------------------------------------------------------ background

@Composable
fun GlassBg(drift: Boolean, rate: Float, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        if (drift) AnimatedBlobs(rate) else BlobLayer { 1.7f }
        content()
    }
}

@Composable
private fun AnimatedBlobs(rate: Float) {
    var phase by remember { mutableStateOf(1.7f) }
    val r by rememberUpdatedState(rate)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) phase += (t - last) / 1_000_000_000f * r
                last = t
            }
        }
    }
    BlobLayer { phase }
}

@Composable
private fun BlobLayer(phase: () -> Float) {
    Canvas(Modifier.fillMaxSize().blur(60.dp)) {
        val p = phase()
        val w = size.width
        val h = size.height
        drawCircle(
            Color.White.copy(alpha = 0.14f), radius = w * 0.55f,
            center = Offset(w * (0.5f + 0.4f * sin(p * 0.9f)), h * (0.22f + 0.14f * cos(p * 0.7f)))
        )
        drawCircle(
            Color.White.copy(alpha = 0.09f), radius = w * 0.6f,
            center = Offset(w * (0.5f + 0.45f * cos(p * 0.6f + 1f)), h * (0.62f + 0.16f * sin(p * 0.8f + 2f)))
        )
        drawCircle(
            Color.White.copy(alpha = 0.07f), radius = w * 0.5f,
            center = Offset(w * (0.5f + 0.4f * sin(p * 0.5f + 4f)), h * (0.95f + 0.08f * cos(p * 0.9f)))
        )
    }
}

// ------------------------------------------------------------------ glass + buttons

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val tilt = LocalTilt.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (strong) Pal.FillStrong else Pal.Fill)
            .border(1.dp, Pal.Line, shape)
            .drawBehind {
                val inset = radius.toPx() * 0.9f
                val span = size.width - 2f * inset
                if (span > 0f) {
                    val y = 1.dp.toPx()
                    drawLine(
                        Color.White.copy(alpha = 0.16f),
                        Offset(inset, y), Offset(size.width - inset, y),
                        1.dp.toPx(), StrokeCap.Round
                    )
                    val c = size.width / 2f + tilt.value * span * 0.35f
                    val half = span * 0.22f
                    val x1 = maxOf(inset, c - half)
                    val x2 = minOf(size.width - inset, c + half)
                    if (x2 > x1) {
                        drawLine(
                            Color.White.copy(alpha = 0.7f),
                            Offset(x1, y), Offset(x2, y),
                            1.5.dp.toPx(), StrokeCap.Round
                        )
                    }
                }
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
            .background(Pal.Ink)
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
            .background(Pal.Fill)
            .border(1.dp, Pal.Line, shape)
            .padding(horizontal = 22.dp, vertical = 14.dp),
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
        targetValue = if (on) Pal.Ink else Color.White.copy(alpha = 0.18f),
        animationSpec = tween(200),
        label = "swc"
    )
    val thumb by animateColorAsState(
        targetValue = if (on) Pal.Bg else Color.White,
        animationSpec = tween(200),
        label = "swt"
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
                .background(thumb)
        )
    }
}

// ------------------------------------------------------------------ icons

@Composable
fun BoltIcon(modifier: Modifier = Modifier, color: Color = Pal.Ink) {
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
        val sw = w * 0.11f
        val tip = if (up) h * 0.1f else h * 0.9f
        val tail = if (up) h * 0.9f else h * 0.1f
        val wing = tip + (if (up) 1f else -1f) * h * 0.28f
        drawLine(color, Offset(w / 2f, tail), Offset(w / 2f, tip), sw, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, tip), Offset(w * 0.2f, wing), sw, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, tip), Offset(w * 0.8f, wing), sw, StrokeCap.Round)
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
fun GlobeIcon(modifier: Modifier = Modifier, color: Color = Pal.Ink) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        val r = minOf(w, h) / 2f - sw
        val c = Offset(w / 2f, h / 2f)
        drawCircle(color, r, c, style = Stroke(sw))
        drawOval(color, Offset(c.x - r * 0.45f, c.y - r), Size(r * 0.9f, r * 2f), style = Stroke(sw))
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), sw)
        drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), sw)
    }
}

@Composable
fun SparkIcon(modifier: Modifier = Modifier, filled: Boolean, color: Color = Pal.Ink) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.62f, h * 0.38f)
            lineTo(w * 0.96f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.62f)
            lineTo(w * 0.5f, h * 0.96f)
            lineTo(w * 0.38f, h * 0.62f)
            lineTo(w * 0.04f, h * 0.5f)
            lineTo(w * 0.38f, h * 0.38f)
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(1.6.dp.toPx()))
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
        val sw = 5.dp.toPx()
        val d = size.minDimension - sw
        drawCircle(Color.White.copy(alpha = 0.12f), radius = d / 2f, style = Stroke(sw))
        drawArc(
            color = Pal.Ink,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(d, d),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun FloatingBolt(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "float")
    val dy by t.animateFloat(
        -8f, 8f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy"
    )
    val rot by t.animateFloat(
        -6f, 6f,
        infiniteRepeatable(tween(3100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rot"
    )
    BoltIcon(
        modifier.graphicsLayer {
            translationY = dy
            rotationZ = rot
        },
        Pal.Ink
    )
}

// ------------------------------------------------------------------ pairing + transfer visuals

/** Rings that pulse outward. [startFrac] is where they begin, [spread] how far they travel (1 = the canvas edge). */
@Composable
fun PulseRings(modifier: Modifier = Modifier, startFrac: Float = 0.45f, spread: Float = 1f) {
    val t = rememberInfiniteTransition(label = "rings")
    val p by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "p"
    )
    Canvas(modifier) {
        val half = size.minDimension / 2f
        val minR = half * startFrac
        val maxR = half * spread
        for (i in 0 until 3) {
            val f = (p + i / 3f) % 1f
            drawCircle(
                Color.White.copy(alpha = (1f - f) * 0.3f),
                radius = minR + (maxR - minR) * f,
                center = center,
                style = Stroke(1.5.dp.toPx())
            )
        }
    }
}

/**
 * Two phones. While [active] is false a dot searches along a dashed line;
 * once active, squares stream from the sender to the receiver, more of them as [flow] (0..1) grows.
 */
@Composable
fun PhoneLink(modifier: Modifier = Modifier, active: Boolean, flow: Float, youAreSender: Boolean) {
    val t = rememberInfiniteTransition(label = "link")
    val p by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "p"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val phoneH = h * 0.92f
        val phoneW = phoneH * 0.52f
        val lx = w * 0.14f
        val rx = w * 0.86f - phoneW
        val top = (h - phoneH) / 2f
        val sw = 2.dp.toPx()
        val corner = CornerRadius(phoneW * 0.2f)
        drawRoundRect(
            Color.White.copy(alpha = if (youAreSender) 0.95f else 0.5f),
            Offset(lx, top), Size(phoneW, phoneH), corner, style = Stroke(sw)
        )
        drawRoundRect(
            Color.White.copy(alpha = if (youAreSender) 0.5f else 0.95f),
            Offset(rx, top), Size(phoneW, phoneH), corner, style = Stroke(sw)
        )
        val x0 = lx + phoneW + 8.dp.toPx()
        val x1 = rx - 8.dp.toPx()
        val y = h / 2f
        val pi = Math.PI.toFloat()
        if (!active) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()), p * 14.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.35f), Offset(x0, y), Offset(x1, y), sw, StrokeCap.Butt, dash)
            drawCircle(Color.White, 3.dp.toPx(), Offset(x0 + (x1 - x0) * p, y))
        } else {
            drawLine(Color.White.copy(alpha = 0.22f), Offset(x0, y), Offset(x1, y), sw, StrokeCap.Round)
            val f01 = flow.coerceIn(0f, 1f)
            val n = 3 + (f01 * 9f).toInt()
            val half = 2.5.dp.toPx()
            for (i in 0 until n) {
                val f = (p * (1f + f01) + i.toFloat() / n) % 1f
                val px = x0 + (x1 - x0) * f
                val a = sin(f * pi).coerceIn(0f, 1f)
                val py = y - a * phoneH * 0.22f
                drawRect(
                    Color.White.copy(alpha = 0.25f + 0.65f * a),
                    Offset(px - half, py - half), Size(half * 2f, half * 2f)
                )
            }
        }
    }
}

/** A glass disc that fills with two waves; waves get taller and faster with [intensity] (0..1). */
@Composable
fun LiquidGauge(
    progress: Float,
    intensity: Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "level")
    val inten by animateFloatAsState(intensity.coerceIn(0f, 1f), tween(400), label = "inten")
    var phase by remember { mutableStateOf(0f) }
    val rate by rememberUpdatedState(1.6f + inten * 5f)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) phase += (t - last) / 1_000_000_000f * rate
                last = t
            }
        }
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Pal.Fill)
            .border(1.dp, Pal.Line, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val level = h * (1f - p)
            val twoPi = (2.0 * Math.PI).toFloat()
            val ampA = (3f + 9f * inten).dp.toPx()
            val ampB = (2f + 6f * inten).dp.toPx()
            fun wave(shift: Float, amp: Float, color: Color) {
                val path = Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w) {
                    val y = level + amp * sin(x / w * twoPi * 1.4f + phase + shift)
                    path.lineTo(x, y)
                    x += 6f
                }
                path.lineTo(w, h)
                path.close()
                drawPath(path, color)
            }
            wave(0f, ampA, Color.White.copy(alpha = 0.26f))
            wave(2.2f, ampB, Color.White.copy(alpha = 0.16f))
        }
        content()
    }
}

/** Digits that roll like an odometer when the text changes. */
@Composable
fun RollingText(text: String, fontSize: TextUnit, weight: FontWeight, color: Color) {
    Row {
        for (ch in text) {
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    (slideInVertically(animationSpec = tween(220), initialOffsetY = { it / 2 }) +
                        fadeIn(tween(220))) togetherWith
                        (slideOutVertically(animationSpec = tween(220), targetOffsetY = { -it / 2 }) +
                            fadeOut(tween(220)))
                },
                label = "digit"
            ) { c ->
                Text(
                    text = c.toString(),
                    style = TextStyle(
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = color,
                        fontFeatureSettings = "tnum"
                    )
                )
            }
        }
    }
}

/** Per-file progress ring; turns into a solid tick when done. */
@Composable
fun RingProgress(progress: Float, modifier: Modifier = Modifier, done: Boolean = false) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "ring")
    Canvas(modifier) {
        val sw = 2.5.dp.toPx()
        val d = size.minDimension - sw
        if (done) {
            drawCircle(Color.White, radius = size.minDimension / 2f)
            val w = size.width
            val h = size.height
            val s = w * 0.12f
            drawLine(Color.Black, Offset(w * 0.28f, h * 0.52f), Offset(w * 0.44f, h * 0.68f), s, StrokeCap.Round)
            drawLine(Color.Black, Offset(w * 0.44f, h * 0.68f), Offset(w * 0.74f, h * 0.34f), s, StrokeCap.Round)
        } else {
            drawCircle(Color.White.copy(alpha = 0.2f), radius = d / 2f, style = Stroke(sw))
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * p,
                useCenter = false,
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(d, d),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
    }
}

private class Particle(
    val angle: Float,
    val speed: Float,
    val size: Float,
    val kind: Int,
    val spin: Float,
    val shade: Float
)

/** One-shot burst of flat white shapes. */
@Composable
fun Confetti(modifier: Modifier = Modifier) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing)) }
    val parts = remember {
        val r = java.util.Random(11)
        List(36) {
            Particle(
                angle = r.nextFloat() * 6.2832f,
                speed = 0.35f + r.nextFloat() * 0.65f,
                size = 4f + r.nextFloat() * 6f,
                kind = r.nextInt(3),
                spin = r.nextFloat() * 540f - 270f,
                shade = 0.45f + r.nextFloat() * 0.55f
            )
        }
    }
    Canvas(modifier) {
        val c = center
        val maxD = size.minDimension * 0.5f
        val p = prog.value
        for (q in parts) {
            val d = maxD * q.speed * p
            val x = c.x + cos(q.angle) * d
            val y = c.y + sin(q.angle) * d + 60.dp.toPx() * p * p
            val a = (1f - p).coerceIn(0f, 1f) * q.shade
            val s = q.size.dp.toPx()
            val col = Color.White.copy(alpha = a)
            when (q.kind) {
                0 -> drawCircle(col, s / 2f, Offset(x, y))
                1 -> rotate(q.spin * p, Offset(x, y)) {
                    drawRect(col, Offset(x - s / 2f, y - s / 2f), Size(s, s))
                }
                else -> rotate(q.spin * p, Offset(x, y)) {
                    drawRect(col, Offset(x - s, y - s / 4f), Size(s * 2f, s / 2f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ QR

fun makeQr(text: String): Bitmap {
    val hints = HashMap<EncodeHintType, Any>()
    hints[EncodeHintType.MARGIN] = 0
    hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640, hints)
    val w = m.width
    val h = m.height
    val dark = 0xFF000000.toInt()
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
