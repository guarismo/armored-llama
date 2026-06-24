package com.iguar.armedllama.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A circular progress ring with arbitrary centre content (the % + label). Matches the README's
 * RAM/CPU rings: a track arc plus a rounded progress arc swept from 12 o'clock.
 */
@Composable
fun ProgressRing(
    progress: Float,
    diameter: Dp,
    color: Color,
    trackColor: Color,
    strokeWidth: Dp = 9.dp,
    center: @Composable () -> Unit = {},
) {
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        center()
    }
}

/**
 * A filled sparkline of normalised (0..1) samples, oldest→newest. Draws a soft gradient fill
 * under a stroked line, as in the RAM "over time" chart and the B-grid stat cards.
 */
@Composable
fun Sparkline(
    samples: List<Float>,
    modifier: Modifier = Modifier,
    color: Color,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val pad = strokeWidth.toPx()
        val usableH = h - pad * 2
        val stepX = w / (samples.size - 1)

        fun y(v: Float) = pad + (1f - v.coerceIn(0f, 1f)) * usableH

        val line = Path().apply {
            moveTo(0f, y(samples.first()))
            for (i in 1 until samples.size) lineTo(i * stepX, y(samples[i]))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
            ),
        )
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** A rounded horizontal progress bar with a 2-stop gradient fill (cores, GPU, downloads). */
@Composable
fun GradientBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    startColor: Color,
    endColor: Color,
    trackColor: Color,
) {
    Canvas(modifier = modifier) {
        val h = size.height
        val r = h / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        val fillW = (size.width * fraction.coerceIn(0f, 1f))
        if (fillW > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(startColor, endColor),
                    startX = 0f,
                    endX = size.width,
                ),
                size = Size(fillW.coerceAtLeast(h), h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        }
    }
}
