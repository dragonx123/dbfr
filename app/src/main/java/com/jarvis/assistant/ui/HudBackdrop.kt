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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.random.Random

private const val POOL = 64
private const val MAX_COLUMNS = 64

/** Per-column rain parameters, fixed for the life of the composable. */
private data class ColumnSeed(
    val speedRows: Float,
    val trail: Int,
    val phase: Float,
    val gapRows: Float,
)

/**
 * Which glyph sits in a given cell. Deterministic from the coordinates, so
 * a character stays put as the drop passes over it rather than the whole
 * column reshuffling every frame — except one cell in eight, which is
 * re-rolled a few times a second to get the characteristic flicker.
 */
private fun glyphIndex(col: Int, row: Int, timeSec: Float, alphabetSize: Int): Int {
    var hash = col * 73856093 xor row * 19349663
    if ((hash and 7) == 0) hash = hash xor ((timeSec * 6f).toInt() * 83492791)
    return ((hash ushr 3) and 0x7FFFFFFF) % alphabetSize
}

/**
 * The decorative half of the "futuristic HUD" look: a low-alpha layer meant
 * to sit BEHIND screen content — code glyphs raining down the full width of
 * the screen, thin corner brackets, and a slow scanline sweep, tinted with
 * the active persona's color.
 *
 * Each column runs its own drop at its own speed with a fading trail and a
 * near-white leading glyph. Cost is bounded by [MAX_COLUMNS] and the trail
 * lengths (a few hundred single-character draws per frame, reusing one
 * paint and one char buffer so nothing allocates in the draw loop). It
 * stays deliberately dim so chat text keeps full contrast against black.
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

    // Single-character alphabet: rain is per-cell glyphs, not words.
    val alphabet = remember { "0123456789ABCDEF<>/{}[]()=+-*#%$&@!?;:._|\\^~".toCharArray() }

    // Per-column fall speed, trail length and phase, generated once for a
    // fixed pool and indexed modulo the actual column count — so the look
    // doesn't change (or need recomputing) when the canvas is resized.
    val columnSeeds = remember {
        val rng = Random(1974)
        List(POOL) {
            ColumnSeed(
                speedRows = rng.nextFloat() * 9f + 5f,   // rows per second
                trail = rng.nextInt(7, 18),
                phase = rng.nextFloat(),
                gapRows = rng.nextFloat() * 14f + 4f,    // dark gap between drops
            )
        }
    }

    val density = LocalDensity.current
    val textSizePx = with(density) { 11.dp.toPx() }
    // One reusable buffer: drawText(char[]) avoids allocating a String per
    // glyph, and there are several hundred glyphs on screen every frame.
    val glyphBuffer = remember { CharArray(1) }
    val paint = remember {
        android.graphics.Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
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

        // -- Matrix rain -----------------------------------------------------
        // Independent drops falling down every column, each with a bright
        // leading glyph and a trail fading out behind it.
        paint.textSize = textSizePx
        val cellW = textSizePx * 1.15f
        val rowH = textSizePx * 1.25f
        val columns = (w / cellW).toInt().coerceIn(1, MAX_COLUMNS)
        val rows = (h / rowH).toInt() + 1
        val native = drawContext.canvas.nativeCanvas
        // Head is near-white so the leading edge reads as light rather than
        // just a brighter shade of the persona color.
        val headArgb = lerp(color, Color.White, 0.75f).copy(alpha = (glyphAlpha * 4.5f).coerceAtMost(0.85f)).toArgb()

        for (col in 0 until columns) {
            val seed = columnSeeds[col % POOL]
            val cycleRows = rows + seed.trail + seed.gapRows
            // Wrapping position of this column's drop head, in rows.
            val headRow = floor(
                ((timeSec * seed.speedRows / cycleRows) + seed.phase).let { it - floor(it) } * cycleRows
            ).toInt() - seed.trail
            val x = col * cellW + cellW / 2f

            for (t in 0 until seed.trail) {
                val row = headRow - t
                if (row < 0 || row >= rows) continue
                val fade = 1f - t / seed.trail.toFloat()
                val isHead = t == 0
                paint.color = if (isHead) headArgb else {
                    // Coerced because Color.copy rejects alpha outside 0..1,
                    // and the trail multiplier is tuned for the default dim
                    // glyphAlpha rather than clamped by construction.
                    color.copy(alpha = (glyphAlpha * fade * fade * 3.2f).coerceIn(0f, 1f)).toArgb()
                }
                glyphBuffer[0] = alphabet[glyphIndex(col, row, timeSec, alphabet.size)]
                native.drawText(glyphBuffer, 0, 1, x, row * rowH + rowH, paint)
            }
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
