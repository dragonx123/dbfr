package com.jarvis.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.jarvis.assistant.model.Persona
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Which phase of a voice-mode turn the orb is currently visualizing. */
enum class OrbPhase { IDLE, LISTENING, THINKING, SPEAKING, COOLDOWN, MUTED }

/** [Persona.orbColorArgb] converted to a Compose color, for use in the UI layer. */
val Persona.orbColor: Color
    get() = Color(orbColorArgb)

private data class Point3D(val x: Float, val y: Float, val z: Float)

/** Evenly distributes [count] points on a unit sphere via the golden-angle method. */
private fun fibonacciSphere(count: Int): List<Point3D> {
    val goldenAngle = PI.toFloat() * (3f - sqrt(5f))
    return List(count) { i ->
        val y = 1f - (i / (count - 1).toFloat()) * 2f
        val radiusAtY = sqrt((1f - y * y).coerceAtLeast(0f))
        val theta = goldenAngle * i
        Point3D(cos(theta) * radiusAtY, y, sin(theta) * radiusAtY)
    }
}

/** Links each point to its [neighborsPerPoint] nearest neighbors, deduped into undirected edges. */
private fun buildEdges(points: List<Point3D>, neighborsPerPoint: Int): List<Pair<Int, Int>> {
    fun distSq(a: Point3D, b: Point3D): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }
    val edgeSet = LinkedHashSet<Pair<Int, Int>>()
    for (i in points.indices) {
        val nearest = points.indices
            .filter { it != i }
            .sortedBy { j -> distSq(points[i], points[j]) }
            .take(neighborsPerPoint)
        for (j in nearest) edgeSet.add(if (i < j) i to j else j to i)
    }
    return edgeSet.toList()
}

/**
 * The full-screen voice-mode "plexus sphere": a rotating wireframe globe of
 * glowing points, styled after a JARVIS-style HUD. Reacts to [phase]:
 * - [OrbPhase.LISTENING] is driven by the real (smoothed) microphone level
 *   in [micLevel] — see `SpeechToText`'s `onRmsChanged` hook.
 * - [OrbPhase.SPEAKING] has no real audio-amplitude source (Android's TTS
 *   exposes none), so it's a procedural "talking" texture instead.
 * - [OrbPhase.THINKING]/[OrbPhase.IDLE]/[OrbPhase.COOLDOWN]/[OrbPhase.MUTED]
 *   each get a distinct calmer breathing rhythm.
 *
 * Pure `Canvas`/`DrawScope` — no new dependencies, no bitmap blur, so it
 * runs on every supported API level.
 */
@Composable
fun PlexusOrb(
    phase: OrbPhase,
    micLevel: Float,
    color: Color,
    modifier: Modifier = Modifier,
    pointCount: Int = 120,
    neighborsPerPoint: Int = 3,
) {
    val points = remember(pointCount) { fibonacciSphere(pointCount) }
    val edges = remember(points, neighborsPerPoint) { buildEdges(points, neighborsPerPoint) }

    // Always-current views of the composable's parameters, safe to read from the
    // long-lived frame-loop coroutine below (a plain captured Float/enum would
    // otherwise freeze at whatever value was current when the effect first launched).
    val currentMicLevel = rememberUpdatedState(micLevel)
    val currentPhase = rememberUpdatedState(phase)

    var clock by remember { mutableFloatStateOf(0f) }
    var rotationY by remember { mutableFloatStateOf(0f) }
    var rotationX by remember { mutableFloatStateOf(0f) }
    var micSmoothed by remember { mutableFloatStateOf(0f) }
    var energySmoothed by remember { mutableFloatStateOf(0.1f) }

    // Fast-attack / slow-decay "syllable pulse" envelope, only active while speaking —
    // this is what gives SPEAKING a percussive talking feel instead of a smooth blob.
    val speakPulse = remember { Animatable(0.3f) }
    LaunchedEffect(phase) {
        if (phase == OrbPhase.SPEAKING) {
            while (isActive) {
                speakPulse.animateTo(
                    targetValue = Random.nextFloat() * 0.65f + 0.35f,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
                )
                delay(Random.nextLong(80, 220))
                speakPulse.animateTo(
                    targetValue = Random.nextFloat() * 0.2f + 0.15f,
                    animationSpec = tween(Random.nextInt(250, 450)),
                )
                delay(Random.nextLong(40, 140))
            }
        } else {
            speakPulse.animateTo(0.3f, animationSpec = tween(400))
        }
    }

    // The single clock driving rotation and every phase's breathing/texture math.
    LaunchedEffect(Unit) {
        var lastFrameNanos = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { frameNanos ->
                val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrameNanos = frameNanos
                clock += dt

                val t = clock
                val rawEnergy = when (currentPhase.value) {
                    OrbPhase.IDLE ->
                        0.08f + 0.05f * (sin(t * (2f * PI.toFloat() / 4f)) * 0.5f + 0.5f)
                    OrbPhase.LISTENING -> {
                        val target = currentMicLevel.value.coerceIn(0f, 1f)
                        micSmoothed += (target - micSmoothed) * min(1f, dt * 8f)
                        micSmoothed
                    }
                    OrbPhase.THINKING ->
                        0.30f + 0.10f * sin(t * (2f * PI.toFloat() / 1.4f))
                    OrbPhase.SPEAKING -> {
                        val texture = (
                            sin(t * 2f * PI.toFloat() / 1.7f) +
                                sin(t * 2f * PI.toFloat() / 2.3f + 1.3f) +
                                sin(t * 2f * PI.toFloat() / 0.97f + 2.7f)
                            ) / 3f
                        val normalizedTexture = texture * 0.5f + 0.5f
                        (normalizedTexture * 0.35f + speakPulse.value * 0.65f).coerceIn(0f, 1f)
                    }
                    OrbPhase.COOLDOWN, OrbPhase.MUTED -> 0.08f
                }
                energySmoothed += (rawEnergy - energySmoothed) * min(1f, dt * 4f)

                val speed = 0.35f // base radians/sec
                rotationY += speed * (1f + energySmoothed * 2.5f) * dt
                rotationX += speed * 0.35f * (1f + energySmoothed * 1.2f) * dt
            }
        }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = min(size.width, size.height) * 0.42f
        val energy = energySmoothed
        val dim = if (phase == OrbPhase.MUTED) 0.45f else 1f
        val jitterAmount = 0.04f + energy * 0.5f

        val cosY = cos(rotationY); val sinY = sin(rotationY)
        val cosX = cos(rotationX); val sinX = sin(rotationX)

        val screenX = FloatArray(points.size)
        val screenY = FloatArray(points.size)
        val alphas = FloatArray(points.size)

        for (i in points.indices) {
            val p = points[i]
            val x1 = p.x * cosY + p.z * sinY
            val z1 = -p.x * sinY + p.z * cosY
            val y2 = p.y * cosX - z1 * sinX
            val z2 = p.y * sinX + z1 * cosX

            val noise = sin(clock * 3f + i * 0.7f) * 0.5f + sin(clock * 5.3f + i * 1.3f) * 0.3f
            val r = 1f + jitterAmount * noise
            val sx = x1 * r
            val sy = y2 * r
            val sz = z2 * r

            val perspective = 1f + sz * 0.15f
            screenX[i] = cx + sx * baseRadius * perspective
            screenY[i] = cy + sy * baseRadius * perspective

            val depthT = ((sz + 1f) / 2f).coerceIn(0f, 1f)
            alphas[i] = lerp(0.12f, 1f, depthT.pow(1.3f)) * dim
        }

        val haloAlpha = (0.10f + energy * 0.22f).coerceIn(0f, 0.4f) * dim
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = haloAlpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = baseRadius * 1.9f,
            ),
            radius = baseRadius * 1.9f,
            center = Offset(cx, cy),
        )

        val outerStroke = 3.dp.toPx()
        val innerStroke = 1.2.dp.toPx()
        for ((a, b) in edges) {
            val edgeAlpha = min(alphas[a], alphas[b])
            if (edgeAlpha < 0.03f) continue
            val start = Offset(screenX[a], screenY[a])
            val end = Offset(screenX[b], screenY[b])
            drawLine(color.copy(alpha = edgeAlpha * 0.25f), start, end, strokeWidth = outerStroke, cap = StrokeCap.Round)
            drawLine(color.copy(alpha = edgeAlpha * 0.9f), start, end, strokeWidth = innerStroke, cap = StrokeCap.Round)
        }

        val outerDot = 2.5.dp.toPx()
        val innerDot = 0.9.dp.toPx()
        for (i in points.indices) {
            val a = alphas[i]
            if (a < 0.05f) continue
            val center = Offset(screenX[i], screenY[i])
            drawCircle(color.copy(alpha = a * 0.3f), radius = outerDot, center = center)
            drawCircle(color.copy(alpha = a), radius = innerDot, center = center)
        }
    }
}
