package com.jarvis.assistant.ui

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.random.Random

/**
 * The decorative half of the "futuristic HUD" look: a very low-alpha layer
 * meant to sit BEHIND screen content — a drifting column of code/hex glyphs
 * down the right edge, thin corner brackets, and a slow scanline sweep.
 * Tinted with the active persona's color. All draws are cheap (one text
 * paint, a handful of lines); it deliberately stays subtle so the actual
 * UI keeps full contrast on the pure-black background.
 */
@Composable
fun HudBackdrop(
    color: Color,
    modifier: Modifier = Modifier,
    glyphAlpha: Float = 0.10f,
    showScanline: Boolean = true,
) {
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                timeSec = (now - start) / 1_000_000_000f
            }
        }
    }

    // A fixed pool of pseudo-code glyph strings, generated once.
    val glyphs = remember {
        val rng = Random(1974)
        val alphabet = "0123456789ABCDEF<>/{}[]()=+-*#%$&@!?;:._|\\^~"
        List(64) { (0 until rng.nextInt(3, 9)).map { alphabet.random(rng) }.joinToString("") }
    }

    val density = LocalDensity.current
    val textSizePx = with(density) { 9.dp.toPx() }
    val paint = remember(color) {
        android.graphics.Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bracket = 18.dp.toPx()
        val inset = 10.dp.toPx()
        val bracketColor = color.copy(alpha = 0.28f)
        val stroke = 1.5.dp.toPx()

        // -- Corner brackets ------------------------------------------------
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(bracketColor, Offset(x, y), Offset(x + bracket * dx, y), stroke)
            drawLine(bracketColor, Offset(x, y), Offset(x, y + bracket * dy), stroke)
        }
        corner(inset, inset, 1f, 1f)
        corner(w - inset, inset, -1f, 1f)
        corner(inset, h - inset, 1f, -1f)
        corner(w - inset, h - inset, -1f, -1f)

        // -- Drifting glyph column down the right edge ----------------------
        paint.textSize = textSizePx
        paint.color = color.copy(alpha = glyphAlpha).toArgb()
        val lineH = textSizePx * 1.65f
        val columnX = w - 8.dp.toPx()
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        val scroll = timeSec * lineH * 0.55f // slow constant drift
        val firstLine = floor(scroll / lineH).toInt()
        var y = -(scroll % lineH)
        var i = firstLine
        val native = drawContext.canvas.nativeCanvas
        while (y < h + lineH) {
            native.drawText(glyphs[((i % glyphs.size) + glyphs.size) % glyphs.size], columnX, y, paint)
            y += lineH
            i++
        }

        // -- Scanline sweep --------------------------------------------------
        if (showScanline) {
            val period = 9f // seconds per sweep
            val sweepY = ((timeSec % period) / period) * (h + 200f) - 100f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, color.copy(alpha = 0.045f), Color.Transparent),
                    startY = sweepY - 60f,
                    endY = sweepY + 60f,
                ),
                topLeft = Offset(0f, sweepY - 60f),
                size = androidx.compose.ui.geometry.Size(w, 120f),
            )
        }
    }
}
