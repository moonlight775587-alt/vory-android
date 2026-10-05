package dev.vory.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.vory.android.data.BotMood
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

/**
 * Code-drawn bot face — the signature Vory visual, redrawn for Android.
 *
 * Body shape (circle / pebble / rounded-square) + eye style variants are picked
 * deterministically from the bot's [seed]; [color] is the per-bot accent.
 *
 * Motion vocabulary (mirrors the original's one motion rule: nothing scales,
 * bounces, hops or pops):
 * - Idle: blink every ~4s (occasionally a double blink), glance every ~7s.
 * - Working: slow coin-turn tilt / gentle nod.
 * - Approval waiting: bold "!".
 * - Error: eyes drop.
 * - Reduced motion (or motion="still"): still bodies, blinking eyes only.
 */
@Composable
fun BotFace(
    seed: Int,
    color: Color,
    mood: BotMood,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    motion: String = "lively",
) {
    val bodyShape = remember(seed) { abs(seed) % 3 }          // 0 circle, 1 pebble, 2 squircle
    val eyeStyle = remember(seed) { (abs(seed) / 3) % 3 }     // 0 dots, 1 bars, 2 arcs
    val reducedMotion = rememberReducedMotion()
    val lively = motion == "lively" && !reducedMotion
    val calm = motion == "calm" && !reducedMotion
    val animateBody = lively

    // Blink: eyelid closure 0..1, driven by a timer loop (~4s, sometimes double).
    val blink = remember { Animatable(0f) }
    LaunchedEffect(seed, reducedMotion) {
        val r = Random(seed.toLong() + 99)
        while (true) {
            delay(3200 + r.nextLong(1800))
            val speed = if (reducedMotion) 2 else 1
            blink.animateTo(1f, tween(90 * speed, easing = LinearEasing))
            blink.animateTo(0f, tween(120 * speed, easing = LinearEasing))
            if (r.nextFloat() < 0.25) { // occasional double blink
                delay(180)
                blink.animateTo(1f, tween(80 * speed, easing = LinearEasing))
                blink.animateTo(0f, tween(110 * speed, easing = LinearEasing))
            }
        }
    }

    // Glance: pupils drift sideways every ~7s (lively only).
    val glance = remember { Animatable(0f) }
    LaunchedEffect(seed, lively) {
        if (!lively) return@LaunchedEffect
        val r = Random(seed.toLong() + 7)
        while (true) {
            delay(6000 + r.nextLong(2500))
            val dir = if (r.nextBoolean()) 1f else -1f
            glance.animateTo(dir, tween(500, easing = LinearEasing))
            delay(700)
            glance.animateTo(0f, tween(500, easing = LinearEasing))
        }
    }

    // Working motion: slow coin-turn tilt + gentle nod (transforms only, no scaling).
    val tilt = if (animateBody && mood == BotMood.WORKING) {
        rememberInfiniteTransition(label = "tilt").animateFloat(
            initialValue = -7f, targetValue = 7f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
            label = "tilt",
        ).value
    } else 0f
    val nod = if (animateBody && mood == BotMood.WORKING) {
        rememberInfiniteTransition(label = "nod").animateFloat(
            initialValue = -3f, targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(3100, easing = LinearEasing), RepeatMode.Reverse),
            label = "nod",
        ).value
    } else 0f

    val dark = color.copy(alpha = 1f)
    val deep = Color(
        red = (dark.red * 0.55f).coerceIn(0f, 1f),
        green = (dark.green * 0.55f).coerceIn(0f, 1f),
        blue = (dark.blue * 0.55f).coerceIn(0f, 1f),
    )
    val lidColor = deep

    Canvas(modifier = modifier) {
        val w = size.toPx()
        val cx = w / 2f
        val cy = w / 2f
        rotate(degrees = tilt, pivot = Offset(cx, cy)) {
            translate(top = nod) {
                drawBody(bodyShape, w, dark, deep)
                when (mood) {
                    BotMood.WAITING -> drawExclamation(cx, cy, w)
                    BotMood.ERROR -> drawEyes(eyeStyle, cx, cy, w, glance.value, blink.value, lidColor, droop = true)
                    else -> {
                        drawEyes(eyeStyle, cx, cy, w, glance.value, blink.value, lidColor, droop = false)
                        drawMouth(cx, cy, w, mood)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawBody(shape: Int, w: Float, color: Color, deep: Color) {
    // Subtle finish: radial light from top-left over the base colour.
    val brush = Brush.radialGradient(
        colors = listOf(color, deep),
        center = Offset(w * 0.35f, w * 0.3f),
        radius = w * 0.95f,
    )
    when (shape) {
        0 -> drawCircle(brush, radius = w / 2f, center = Offset(w / 2f, w / 2f))
        1 -> drawRoundRect( // pebble: wide, softly flattened
            brush = brush,
            topLeft = Offset(w * 0.06f, w * 0.14f),
            size = Size(w * 0.88f, w * 0.72f),
            cornerRadius = CornerRadius(w * 0.36f, w * 0.36f),
        )
        else -> drawRoundRect( // rounded square
            brush = brush,
            topLeft = Offset(w * 0.1f, w * 0.1f),
            size = Size(w * 0.8f, w * 0.8f),
            cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
        )
    }
}

private fun DrawScope.drawEyes(
    style: Int, cx: Float, cy: Float, w: Float,
    glance: Float, blink: Float, lidColor: Color, droop: Boolean,
) {
    val eyeY = cy - w * 0.06f
    val dx = w * 0.16f
    val gx = glance * w * 0.05f
    val white = Color.White
    val pupil = Color(0xFF101418)

    fun lid(left: Float, top: Float, width: Float, height: Float) {
        if (blink > 0.02f) {
            drawRect(
                color = lidColor,
                topLeft = Offset(left, top),
                size = Size(width, height * blink),
            )
        }
    }

    when (style) {
        0 -> { // dots
            val r = w * 0.075f
            listOf(cx - dx, cx + dx).forEach { ex ->
                val ey = if (droop) eyeY + w * 0.07f else eyeY
                drawCircle(white, r * 1.9f, Offset(ex, ey))
                drawCircle(pupil, r, Offset(ex + gx, ey))
                lid(ex - r * 1.9f, ey - r * 1.9f, r * 3.8f, r * 3.8f)
            }
        }
        1 -> { // rounded bars
            val bw = w * 0.1f
            val bh = if (droop) w * 0.1f else w * 0.16f
            listOf(cx - dx, cx + dx).forEach { ex ->
                val ey = if (droop) eyeY + w * 0.08f else eyeY - bh / 2f
                drawRoundRect(
                    color = pupil,
                    topLeft = Offset(ex - bw / 2f + gx, ey),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f, bw / 2f),
                )
                lid(ex - bw, ey - bh * 0.4f, bw * 2f, bh * 1.8f)
            }
        }
        else -> { // arcs (happy)
            val arcW = w * 0.16f
            listOf(cx - dx, cx + dx).forEach { ex ->
                if (droop) {
                    // dropped: short downward-angled strokes
                    drawLine(
                        pupil, Offset(ex - arcW / 2f + gx, eyeY + w * 0.02f),
                        Offset(ex + arcW / 2f + gx, eyeY + w * 0.09f),
                        strokeWidth = w * 0.045f,
                    )
                } else {
                    // upward arc using two segments (cheap, no path scaling tricks)
                    val segs = 8
                    var prev = Offset(ex - arcW / 2f + gx, eyeY + w * 0.03f)
                    for (i in 1..segs) {
                        val t = i / segs.toFloat()
                        val px = ex - arcW / 2f + arcW * t + gx
                        val py = eyeY + w * 0.03f - kotlin.math.sin(t * Math.PI).toFloat() * w * 0.05f
                        val cur = Offset(px, py)
                        drawLine(pupil, prev, cur, strokeWidth = w * 0.045f)
                        prev = cur
                    }
                }
                lid(ex - arcW / 2f, eyeY - w * 0.06f, arcW, w * 0.12f)
            }
        }
    }
}

private fun DrawScope.drawMouth(cx: Float, cy: Float, w: Float, mood: BotMood) {
    val mouthY = cy + w * 0.2f
    val color = Color(0xFF101418)
    if (mood == BotMood.WORKING) {
        // small open mouth while working
        drawCircle(color, w * 0.035f, Offset(cx, mouthY))
    } else {
        // gentle smile: segmented arc
        val arcW = w * 0.2f
        val segs = 8
        var prev = Offset(cx - arcW / 2f, mouthY)
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val px = cx - arcW / 2f + arcW * t
            val py = mouthY + kotlin.math.sin(t * Math.PI).toFloat() * w * 0.045f
            val cur = Offset(px, py)
            drawLine(color, prev, cur, strokeWidth = w * 0.04f)
            prev = cur
        }
    }
}

private fun DrawScope.drawExclamation(cx: Float, cy: Float, w: Float) {
    // Bold "!" for approval-waiting — drawn, never popped in.
    val color = Color.White
    val barW = w * 0.11f
    val barH = w * 0.34f
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - barW / 2f, cy - w * 0.24f),
        size = Size(barW, barH),
        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
    )
    drawCircle(color, barW * 0.62f, Offset(cx, cy + w * 0.22f))
}
