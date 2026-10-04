package io.github.escossio.andy.features.presence

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Pure replay: no clocks, mutable random generators, sensors or renderer dependencies. */
object PresenceReplay {
    fun frame(input: PresenceInput): PresenceFrame {
        val time = input.elapsedMillis
        val phase = Math.floorMod(input.seed, 997L).toDouble() / 997.0 * PI * 2.0
        val idle = if ((time / 6_000L) % 2L == 0L) PresenceIdle.REST else PresenceIdle.ATTENTIVE
        val breath = sin((time % 8_000L).toDouble() / 8_000.0 * PI * 2.0 + phase).toFloat()
        val blinkTime = (time % 4_000L + Math.floorMod(input.seed, 1_000L)) % 4_000L
        val openness = if (blinkTime < 200L) abs(blinkTime - 100L) / 100f else 1f
        val start = input.gestureStartedAtMillis
        val cancel = input.gestureCancelledAtMillis
        val age = if (start != null && time >= start) time - start else -1L
        val gesture = when {
            age < 0L -> PresenceGesture.REST
            cancel != null && cancel >= start!! && cancel - start < 2_400L && time >= cancel ->
                PresenceGesture.INTERRUPTED
            age >= 2_400L -> PresenceGesture.FINISHED
            else -> PresenceGesture.WAVING
        }
        val arm = if (gesture == PresenceGesture.WAVING) {
            val envelope = minOf(age / 350f, (2_400L - age) / 350f, 1f)
            envelope * (65f + 12f * sin(age.toDouble() / 150.0).toFloat())
        } else 0f
        return PresenceFrame(
            idle = idle,
            torsoDegrees = (if (idle == PresenceIdle.REST) -3f else 4f) + breath,
            headDegrees = (if (idle == PresenceIdle.REST) 4f else -5f) + breath * 1.5f,
            jawDegrees = maxOf(0f, sin((time % 3_000L).toDouble() / 3_000.0 * PI * 2.0 + phase).toFloat()) * 3f,
            eyeOpenness = openness,
            gazeX = sin((time % 9_000L).toDouble() / 9_000.0 * PI * 2.0 + phase).toFloat(),
            gazeY = sin((time % 7_000L).toDouble() / 7_000.0 * PI * 2.0 + phase).toFloat() * 0.5f,
            gesture = gesture,
            armDegrees = arm,
            clothing = input.clothing,
        )
    }
}
