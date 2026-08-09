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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.jarvis.assistant.model.Persona
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
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
 * Long diagonal "chord" edges between shell points that are *not* spatial
 * neighbors (picked by angular separation, since every shell point is a
 * unit vector so the dot product is the cosine of the angle between them).
 * These are what break the neat, evenly-spaced geodesic-mesh look into
 * something closer to a chaotic circuit trace.
 */
private fun buildChordEdges(points: List<Point3D>, count: Int, seed: Long = 42L): List<Pair<Int, Int>> {
    val random = Random(seed)
    val n = points.size
    if (n < 2 || count <= 0) return emptyList()
    val result = LinkedHashSet<Pair<Int, Int>>()
    var attempts = 0
    while (result.size < count && attempts < count * 40) {
        attempts++
        val i = random.nextInt(n)
        val j = random.nextInt(n)
        if (i == j) continue
        val a = points[i]; val b = points[j]
        val dot = a.x * b.x + a.y * b.y + a.z * b.z // both unit vectors -> cos(angle between them)
        if (dot in -0.1736f..0.9063f) { // roughly 25°..100° apart
            result.add(if (i < j) i to j else j to i)
        }
    }
    return result.toList()
}

/** Precomputed volumetric particle cloud sampled inside the sphere, biased toward its center. */
private class CoreCloud(
    val points: List<Point3D>,
    /** 0..1, how close to the very center each point is — brighter/hotter near 1. */
    val coreT: FloatArray,
    /** Per-point size multiplier in dp, for visual variety. */
    val sizeDp: FloatArray,
    /** A minority of points get an extra soft glow ring, like a spark catching the light. */
    val isSpark: BooleanArray,
)

private fun buildCoreCloud(count: Int, maxRadius: Float, seed: Long = 7L): CoreCloud {
    val random = Random(seed)
    val points = ArrayList<Point3D>(count)
    val coreT = FloatArray(count)
    val sizeDp = FloatArray(count)
    val isSpark = BooleanArray(count)
    repeat(count) { idx ->
        val theta = random.nextFloat() * 2f * PI.toFloat()
        val z = random.nextFloat() * 2f - 1f
        val ringRadius = sqrt((1f - z * z).coerceAtLeast(0f))
        val radiusFraction = random.nextFloat().pow(1.8f) // biased toward the center
        val radius = maxRadius * radiusFraction
        points += Point3D(cos(theta) * ringRadius * radius, sin(theta) * ringRadius * radius, z * radius)
        coreT[idx] = (1f - radiusFraction).coerceIn(0f, 1f).pow(1.5f)
        sizeDp[idx] = random.nextFloat() * 1.2f + 0.4f
        isSpark[idx] = random.nextInt(6) == 0
    }
    return CoreCloud(points, coreT, sizeDp, isSpark)
}

private const val CORE_CLOUD_MAX_RADIUS = 0.62f

/**
 * The full-screen voice-mode "plexus sphere": a rotating, densely woven
 * globe of glowing points and circuit-like traces around a blazing core,
 * styled after a JARVIS-style HUD. Reacts to [phase]:
 * - [OrbPhase.LISTENING] is driven by the real (smoothed) microphone level
 *   in [micLevel] — see `SpeechToText`'s `onRmsChanged` hook.
 * - [OrbPhase.SPEAKING] has no real audio-amplitude source (Android's TTS
 *   exposes none), so it's a procedural "talking" texture instead.
 * - [OrbPhase.THINKING]/[OrbPhase.IDLE]/[OrbPhase.COOLDOWN]/[OrbPhase.MUTED]
 *   each get a distinct calmer breathing rhythm.
 *
 * Pure `Canvas`/`DrawScope` — no new dependencies, no bitmap blur, so it
 * runs on every supported API level. Brightness/density scale with the
 * same energy value that already drives jitter/rotation, so the effect
 * visibly intensifies while listening/speaking rather than being a static
 * prettier sphere.
 */
@Composable
fun PlexusOrb(
    phase: OrbPhase,
    micLevel: Float,
    color: Color,
    modifier: Modifier = Modifier,
    pointCount: Int = 150,
    neighborsPerPoint: Int = 3,
    chordEdgeCount: Int = 36,
    coreCloudCount: Int = 90,
) {
    val points = remember(pointCount) { fibonacciSphere(pointCount) }
    val edges = remember(points, neighborsPerPoint) { buildEdges(points, neighborsPerPoint) }
    val chordEdges = remember(points, chordEdgeCount) { buildChordEdges(points, chordEdgeCount) }
    val coreCloud = remember(coreCloudCount) { buildCoreCloud(coreCloudCount, CORE_CLOUD_MAX_RADIUS) }

    // Cosmetic per-primitive variety (irregular widths/sizes), precomputed once so
    // the mesh doesn't read as a perfectly uniform, machine-neat wireframe.
    val edgeWidthScale = remember(edges) { FloatArray(edges.size) { Random(it + 500).nextFloat() * 0.8f + 0.6f } }
    val pointSizeScale = remember(points) { FloatArray(points.size) { Random(it + 900).nextFloat() * 0.8f + 0.7f } }

    val hotColor = remember(color) { lerp(color, Color.White, 0.85f) }

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

    // A one-shot expanding shockwave ring on entering an active phase, so
    // transitions (start listening, start speaking) feel like events instead
    // of the sphere just changing its idle math.
    val transitionPulse = remember { Animatable(1f) }
    LaunchedEffect(phase) {
        if (phase == OrbPhase.LISTENING || phase == OrbPhase.THINKING || phase == OrbPhase.SPEAKING) {
            transitionPulse.snapTo(0f)
            transitionPulse.animateTo(1f, animationSpec = tween(650))
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
        val energy = energySmoothed
        // Slow "breathing" of the whole sphere so even IDLE is never a
        // frozen radius; energy adds a slight swell on top while active.
        val breath = 1f + 0.025f * sin(clock * 0.9f) + energy * 0.04f
        val baseRadius = min(size.width, size.height) * 0.42f * breath
        val dim = if (phase == OrbPhase.MUTED) 0.45f else 1f
        val jitterAmount = 0.04f + energy * 0.5f

        // How strongly "energy" pushes the new density/brightness layers, so the
        // upgrade still visibly reacts to listening/speaking rather than just
        // being a static prettier sphere.
        val bloomBoost = lerp(0.7f, 1.4f, energy)
        val coreExponent = lerp(2.6f, 1.6f, energy)
        val coreCloudVisibleFraction = lerp(0.3f, 1f, energy)
        val chordVisibleFraction = lerp(0.2f, 1f, energy)

        val cosY = cos(rotationY); val sinY = sin(rotationY)
        val cosX = cos(rotationX); val sinX = sin(rotationX)

        val screenX = FloatArray(points.size)
        val screenY = FloatArray(points.size)
        val alphas = FloatArray(points.size)
        val coreT = FloatArray(points.size)

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

            // Screen-space distance to center doubles as "how central/hot is this
            // point" — this works because the shape is a sphere, so its silhouette
            // center stays meaningful under any rotation.
            val normalizedDist = (hypot(screenX[i] - cx, screenY[i] - cy) / (baseRadius * 1.15f)).coerceIn(0f, 1f)
            coreT[i] = (1f - normalizedDist).pow(coreExponent)
        }

        // Ambient outer halo (unchanged) plus a tighter, energy-scaled hot-core halo
        // underneath the mesh, so the blazing center doesn't depend entirely on many
        // tiny overlapping primitives to read correctly.
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
        val hotHaloRadius = baseRadius * lerp(0.35f, 0.6f, energy)
        val hotHaloAlpha = (0.18f + energy * 0.35f).coerceIn(0f, 0.55f) * dim
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(hotColor.copy(alpha = hotHaloAlpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = hotHaloRadius,
            ),
            radius = hotHaloRadius,
            center = Offset(cx, cy),
            blendMode = BlendMode.Plus,
        )

        // Primary mesh: nearest-neighbor edges, colored by how central each endpoint
        // is. Outer pass stays normal alpha-blended (soft, wide); inner pass is
        // additive so real crossings/dense areas actually add light together.
        val outerStroke = 3.dp.toPx()
        val innerStroke = 1.1.dp.toPx()
        for (edgeIndex in edges.indices) {
            val (a, b) = edges[edgeIndex]
            val edgeAlpha = min(alphas[a], alphas[b])
            if (edgeAlpha < 0.03f) continue
            val edgeColor = lerp(color, hotColor, (coreT[a] + coreT[b]) * 0.5f)
            val widthScale = edgeWidthScale[edgeIndex]
            val start = Offset(screenX[a], screenY[a])
            val end = Offset(screenX[b], screenY[b])
            drawLine(edgeColor.copy(alpha = edgeAlpha * 0.25f), start, end, strokeWidth = outerStroke * widthScale, cap = StrokeCap.Round)
            drawLine(
                edgeColor.copy(alpha = (edgeAlpha * 0.9f * bloomBoost).coerceIn(0f, 1f)),
                start, end, strokeWidth = innerStroke * widthScale, cap = StrokeCap.Round, blendMode = BlendMode.Plus,
            )
        }

        // Chord edges: long diagonals between non-neighboring points, thinner and
        // dimmer than the primary mesh — reads as background circuitry crossing
        // through the sphere. Density gated by energy, like the core cloud below.
        val chordOuterStroke = 2.dp.toPx()
        val chordInnerStroke = 0.8.dp.toPx()
        val chordCount = (chordEdges.size * chordVisibleFraction).toInt()
        for (chordIndex in 0 until chordCount) {
            val (a, b) = chordEdges[chordIndex]
            val edgeAlpha = min(alphas[a], alphas[b]) * 0.6f
            if (edgeAlpha < 0.03f) continue
            val edgeColor = lerp(color, hotColor, (coreT[a] + coreT[b]) * 0.5f)
            val start = Offset(screenX[a], screenY[a])
            val end = Offset(screenX[b], screenY[b])
            drawLine(edgeColor.copy(alpha = edgeAlpha * 0.2f), start, end, strokeWidth = chordOuterStroke, cap = StrokeCap.Round)
            drawLine(
                edgeColor.copy(alpha = (edgeAlpha * 0.8f * bloomBoost).coerceIn(0f, 1f)),
                start, end, strokeWidth = chordInnerStroke, cap = StrokeCap.Round, blendMode = BlendMode.Plus,
            )
        }

        val outerDot = 2.5.dp.toPx()
        val innerDot = 0.9.dp.toPx()
        for (i in points.indices) {
            val a = alphas[i]
            if (a < 0.05f) continue
            val pointColor = lerp(color, hotColor, coreT[i])
            val scale = pointSizeScale[i]
            val centerPt = Offset(screenX[i], screenY[i])
            drawCircle(pointColor.copy(alpha = a * 0.3f), radius = outerDot * scale, center = centerPt)
            drawCircle(
                pointColor.copy(alpha = (a * bloomBoost).coerceIn(0f, 1f)),
                radius = innerDot * scale, center = centerPt, blendMode = BlendMode.Plus,
            )
        }

        // Core particle cloud: a volumetric speckle of unconnected points inside the
        // sphere, biased toward the center — the cheap way to add a lot of density
        // without paying the O(n·k) cost more edges would. No jitter needed; density
        // itself (via coreCloudVisibleFraction) already makes this feel reactive.
        val cloudVisibleCount = (coreCloud.points.size * coreCloudVisibleFraction).toInt()
        for (i in 0 until cloudVisibleCount) {
            val p = coreCloud.points[i]
            val x1 = p.x * cosY + p.z * sinY
            val z1 = -p.x * sinY + p.z * cosY
            val y2 = p.y * cosX - z1 * sinX
            val z2 = p.y * sinX + z1 * cosX
            val perspective = 1f + z2 * 0.15f
            val px = cx + x1 * baseRadius * perspective
            val py = cy + y2 * baseRadius * perspective

            val depthT = ((z2 / CORE_CLOUD_MAX_RADIUS) * 0.5f + 0.5f).coerceIn(0f, 1f)
            val cloudDepthAlpha = lerp(0.4f, 1f, depthT)
            val cloudColor = lerp(color, hotColor, coreCloud.coreT[i])
            val finalAlpha = (cloudDepthAlpha * bloomBoost).coerceIn(0f, 1f) * dim
            val radiusPx = coreCloud.sizeDp[i].dp.toPx()
            val centerPt = Offset(px, py)

            drawCircle(cloudColor.copy(alpha = finalAlpha), radius = radiusPx, center = centerPt, blendMode = BlendMode.Plus)
            if (coreCloud.isSpark[i]) {
                drawCircle(cloudColor.copy(alpha = (finalAlpha * 0.35f).coerceIn(0f, 1f)), radius = radiusPx * 2.5f, center = centerPt)
            }
        }

        // Precessing equatorial ring: a thin 3D orbit line around the sphere,
        // tilted and slowly wobbling independently of the mesh rotation —
        // reads as the orb's "gyroscope" and keeps the silhouette moving even
        // when the mesh itself is calm.
        run {
            val segments = 48
            val ringR = 1.22f
            val tilt = 0.5f + 0.18f * sin(clock * 0.17f)
            val ringYaw = rotationY * 0.6f + clock * 0.25f
            val cosT = cos(tilt); val sinT = sin(tilt)
            val cosP = cos(ringYaw); val sinP = sin(ringYaw)
            val ringStroke = 1.dp.toPx()
            var prevX = 0f; var prevY = 0f; var prevAlpha = 0f; var hasPrev = false
            for (s in 0..segments) {
                val ang = s / segments.toFloat() * 2f * PI.toFloat()
                val x0 = cos(ang) * ringR
                val z0 = sin(ang) * ringR
                // Tilt around X, then yaw around Y, then the same perspective as the mesh.
                val y1 = -z0 * sinT
                val z1 = z0 * cosT
                val x2 = x0 * cosP + z1 * sinP
                val z2 = -x0 * sinP + z1 * cosP
                val perspective = 1f + z2 * 0.15f
                val px = cx + x2 * baseRadius * perspective
                val py = cy + y1 * baseRadius * perspective
                val depthT = ((z2 / ringR) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val segAlpha = lerp(0.04f, 0.4f, depthT.pow(1.5f)) * dim * (0.5f + energy * 0.8f)
                if (hasPrev) {
                    drawLine(
                        color.copy(alpha = ((prevAlpha + segAlpha) * 0.5f).coerceIn(0f, 1f)),
                        Offset(prevX, prevY), Offset(px, py),
                        strokeWidth = ringStroke, cap = StrokeCap.Round, blendMode = BlendMode.Plus,
                    )
                }
                prevX = px; prevY = py; prevAlpha = segAlpha; hasPrev = true
            }
        }

        // Spark streaks: short-lived bright pulses racing along random chords,
        // like signals firing through circuitry. Deterministic per cycle (the
        // chord is picked by hashing the cycle number), so no per-frame state.
        if (chordEdges.isNotEmpty()) {
            val streakCount = 1 + (energy * 2.5f).toInt()
            for (k in 0 until streakCount) {
                val cycle = clock * (0.5f + 0.17f * k) + k * 7.31f
                val progress = cycle - floor(cycle)
                val rng = Random(floor(cycle).toInt() * 31 + k * 101)
                val (a, b) = chordEdges[rng.nextInt(chordEdges.size)]
                val streakAlpha = min(alphas[a], alphas[b]) * (0.5f + energy * 0.5f)
                if (streakAlpha < 0.05f) continue
                val headT = progress
                val tailT = (progress - 0.22f).coerceAtLeast(0f)
                val hx = lerp(screenX[a], screenX[b], headT)
                val hy = lerp(screenY[a], screenY[b], headT)
                val tx = lerp(screenX[a], screenX[b], tailT)
                val ty = lerp(screenY[a], screenY[b], tailT)
                drawLine(
                    hotColor.copy(alpha = (streakAlpha * 0.8f).coerceIn(0f, 1f)),
                    Offset(tx, ty), Offset(hx, hy),
                    strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round, blendMode = BlendMode.Plus,
                )
                drawCircle(
                    hotColor.copy(alpha = streakAlpha.coerceIn(0f, 1f)),
                    radius = 2.2.dp.toPx(), center = Offset(hx, hy), blendMode = BlendMode.Plus,
                )
            }
        }

        // Transition shockwave: one expanding, fading ring when a new active
        // phase begins (see transitionPulse above).
        val pulse = transitionPulse.value
        if (pulse < 0.995f) {
            val waveRadius = baseRadius * (0.55f + 1.0f * pulse)
            val waveAlpha = ((1f - pulse) * 0.4f) * dim
            drawCircle(
                color = hotColor.copy(alpha = waveAlpha.coerceIn(0f, 1f)),
                radius = waveRadius,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                blendMode = BlendMode.Plus,
            )
        }
    }
}
