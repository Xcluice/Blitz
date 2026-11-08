package com.xcluice.blitz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import kotlin.math.cos
import kotlin.math.sin

const val AVATAR_COUNT = 24

/** 24 original black-and-white faces: 4 head shapes x 6 expressions, with a few toppers mixed in. */
@Composable
fun Avatar(id: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawAvatar(((id % AVATAR_COUNT) + AVATAR_COUNT) % AVATAR_COUNT) }
}

private fun DrawScope.drawAvatar(id: Int) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f + s * 0.05f
    val white = Color.White
    val black = Color.Black
    val shape = id % 4
    val feat = id / 4
    val topper = id % 3

    drawCircle(Color.White.copy(alpha = 0.16f), radius = s / 2f)

    // topper
    val top = cy - s * 0.30f
    when (topper) {
        1 -> {
            drawLine(white, Offset(cx, top), Offset(cx, top - s * 0.13f), s * 0.025f, StrokeCap.Round)
            drawCircle(white, s * 0.04f, Offset(cx, top - s * 0.15f))
        }
        2 -> {
            drawCircle(white, s * 0.07f, Offset(cx - s * 0.27f, cy - s * 0.18f))
            drawCircle(white, s * 0.07f, Offset(cx + s * 0.27f, cy - s * 0.18f))
        }
    }

    // head
    when (shape) {
        0 -> drawCircle(white, s * 0.30f, Offset(cx, cy))
        1 -> drawRoundRect(
            white, Offset(cx - s * 0.29f, cy - s * 0.29f), Size(s * 0.58f, s * 0.58f),
            CornerRadius(s * 0.14f)
        )
        2 -> {
            val p = Path()
            for (i in 0 until 6) {
                val a = (Math.PI / 3.0 * i - Math.PI / 2.0).toFloat()
                val x = cx + cos(a) * s * 0.32f
                val y = cy + sin(a) * s * 0.32f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, white)
        }
        else -> drawOval(white, Offset(cx - s * 0.25f, cy - s * 0.31f), Size(s * 0.5f, s * 0.62f))
    }

    // face
    val ey = cy - s * 0.05f
    val ex = s * 0.105f
    val sw = s * 0.03f
    val my = cy + s * 0.11f
    when (feat) {
        0 -> {
            drawCircle(black, s * 0.035f, Offset(cx - ex, ey))
            drawCircle(black, s * 0.035f, Offset(cx + ex, ey))
            drawArc(
                black, 25f, 130f, false, Offset(cx - s * 0.1f, my - s * 0.07f), Size(s * 0.2f, s * 0.14f),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        1 -> {
            drawLine(black, Offset(cx - ex - s * 0.04f, ey), Offset(cx - ex + s * 0.04f, ey), sw, StrokeCap.Round)
            drawLine(black, Offset(cx + ex - s * 0.04f, ey), Offset(cx + ex + s * 0.04f, ey), sw, StrokeCap.Round)
            drawLine(black, Offset(cx - s * 0.06f, my + s * 0.02f), Offset(cx + s * 0.06f, my + s * 0.02f), sw, StrokeCap.Round)
        }
        2 -> {
            drawCircle(black, s * 0.05f, Offset(cx - ex, ey))
            drawCircle(black, s * 0.05f, Offset(cx + ex, ey))
            drawCircle(black, s * 0.04f, Offset(cx, my + s * 0.02f), style = Stroke(sw))
        }
        3 -> {
            for (sign in intArrayOf(-1, 1)) {
                val x = cx + sign * ex
                drawLine(black, Offset(x - s * 0.04f, ey + s * 0.02f), Offset(x, ey - s * 0.025f), sw, StrokeCap.Round)
                drawLine(black, Offset(x, ey - s * 0.025f), Offset(x + s * 0.04f, ey + s * 0.02f), sw, StrokeCap.Round)
            }
            drawArc(
                black, 0f, 180f, true, Offset(cx - s * 0.09f, my - s * 0.04f), Size(s * 0.18f, s * 0.12f)
            )
        }
        4 -> {
            drawRoundRect(
                black, Offset(cx - s * 0.2f, ey - s * 0.05f), Size(s * 0.4f, s * 0.1f), CornerRadius(s * 0.05f)
            )
            drawCircle(white, s * 0.018f, Offset(cx - ex, ey))
            drawCircle(white, s * 0.018f, Offset(cx + ex, ey))
            drawArc(
                black, 25f, 130f, false, Offset(cx - s * 0.08f, my - s * 0.06f), Size(s * 0.16f, s * 0.12f),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        else -> {
            drawCircle(black, s * 0.07f, Offset(cx, ey), style = Stroke(sw))
            drawCircle(black, s * 0.025f, Offset(cx, ey))
            val z = Path()
            z.moveTo(cx - s * 0.09f, my + s * 0.02f)
            z.lineTo(cx - s * 0.045f, my - s * 0.015f)
            z.lineTo(cx, my + s * 0.02f)
            z.lineTo(cx + s * 0.045f, my - s * 0.015f)
            z.lineTo(cx + s * 0.09f, my + s * 0.02f)
            drawPath(z, black, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}
