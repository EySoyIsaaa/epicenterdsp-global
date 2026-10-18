package com.epicenter.hifi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun KnobControl(
    label: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    unit: String = "",
    size: Dp = 130.dp,
    enabled: Boolean = true,
    showValue: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit
) {
    val totalAngle = 270f
    val startAngle = 135f
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val knobSizePx = with(density) { size.toPx() }
    val latestValue = rememberUpdatedState(value)
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished = rememberUpdatedState(onValueChangeFinished)
    val normalizedFraction = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .pointerInput(enabled, minValue, maxValue, knobSizePx) {
                    if (!enabled) return@pointerInput
                    var dragValue = latestValue.value
                    detectDragGestures(
                        onDragStart = { dragValue = latestValue.value },
                        onDragEnd = { latestOnValueChangeFinished.value?.invoke() },
                        onDragCancel = { latestOnValueChangeFinished.value?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val valuePerPixel = (maxValue - minValue) / knobSizePx
                            dragValue = (dragValue - dragAmount.y * valuePerPixel)
                                .coerceIn(minValue, maxValue)
                            latestOnValueChange.value(dragValue)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = 10.dp.toPx()
                val radius = (size.toPx() - strokeWidth) / 2f
                val centerOffset = Offset(size.toPx() / 2f, size.toPx() / 2f)

                // Polished dark-chrome bezel with a moving metallic reflection.
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFF3D3E41), Color(0xFF55565A), Color(0xFF2A2B2E),
                            Color(0xFF202124), Color(0xFF45464A), Color(0xFF3D3E41)
                        ),
                        center = centerOffset
                    ),
                    radius = radius * 0.91f,
                    center = centerOffset,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = if (darkTheme) listOf(Color(0xFF4A4B4F), Color(0xFF222326), Color(0xFF101113), Color(0xFF09090A))
                        else listOf(Color.White, Color(0xFFE9EAED), Color(0xFFBFC1C6), Color(0xFFF9F9FA)),
                        center = Offset(centerOffset.x * 0.7f, centerOffset.y * 0.62f),
                        radius = radius * 1.45f
                    ),
                    radius = radius * 0.82f,
                    center = centerOffset
                )
                drawCircle(
                    color = (if (darkTheme) Color.White else Color.Black).copy(alpha = 0.07f),
                    radius = radius * 0.82f,
                    center = centerOffset,
                    style = Stroke(width = 1.dp.toPx())
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.035f), Color.Transparent),
                        center = Offset(centerOffset.x * 0.66f, centerOffset.y * 0.62f),
                        radius = radius * 0.42f
                    ),
                    radius = radius * 0.42f,
                    center = Offset(centerOffset.x * 0.66f, centerOffset.y * 0.62f)
                )

                // Segmented red arc like the physical Epicenter control.
                val tickCount = 28
                val tickStep = totalAngle / tickCount
                val tickSweep = tickStep * 0.58f
                val tickRadius = radius * 1.04f
                val activeTicks = (normalizedFraction * tickCount).toInt()
                repeat(tickCount) { index ->
                    drawArc(
                        color = if (index < activeTicks) AccentRed else Color(0xFF351014),
                        startAngle = startAngle + index * tickStep,
                        sweepAngle = tickSweep,
                        useCenter = false,
                        topLeft = Offset(centerOffset.x - tickRadius, centerOffset.y - tickRadius),
                        size = Size(tickRadius * 2, tickRadius * 2),
                        style = Stroke(width = if (knobSizePx >= 150.dp.toPx()) 6.dp.toPx() else 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Dark inset dial with a subtle metallic highlight.
                drawCircle(
                    color = Color.Black.copy(alpha = 0.36f),
                    radius = radius * 0.81f,
                    center = centerOffset,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Bright red needle follows the dial value.
                val currentAngleRad = ((startAngle + totalAngle * normalizedFraction) * PI / 180f).toFloat()
                val needleEnd = Offset(
                    centerOffset.x + radius * 0.69f * cos(currentAngleRad),
                    centerOffset.y + radius * 0.69f * sin(currentAngleRad)
                )
                drawLine(
                    color = AccentRed.copy(alpha = 0.28f),
                    start = Offset(centerOffset.x, centerOffset.y),
                    end = needleEnd,
                    strokeWidth = 10.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = AccentRed,
                    start = Offset(centerOffset.x, centerOffset.y),
                    end = needleEnd,
                    strokeWidth = if (knobSizePx >= 150.dp.toPx()) 5.dp.toPx() else 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(Color(0xFF070708), radius = 7.dp.toPx(), center = centerOffset)
                drawCircle(Color(0xFF343538), radius = 4.dp.toPx(), center = centerOffset, style = Stroke(2.dp.toPx()))
            }

            // Texto central de valor
            if (showValue) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val displayValue = if (unit == "Hz") {
                    value.toInt().toString()
                } else {
                    value.toInt().toString()
                }

                Text(
                    text = "$displayValue$unit",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Etiqueta del Knob
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}
