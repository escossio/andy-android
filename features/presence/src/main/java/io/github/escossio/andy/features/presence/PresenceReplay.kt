package io.github.escossio.andy.features.presence

import kotlin.math.PI
import kotlin.math.sin

/** Pure replay: no clocks, mutable random generators, sensors or renderer dependencies. */
object PresenceReplay {
    fun frame(input: PresenceInput): PresenceFrame {
        val time = input.elapsedMillis
        val phase = Math.floorMod(input.seed, 997L).toDouble() / 997.0 * PI * 2.0
        val idle = if ((time / 6_000L) % 2L == 0L) PresenceIdle.REST else PresenceIdle.ATTENTIVE
        val breath = sin((time % 8_000L).toDouble() / 8_000.0 * PI * 2.0 + phase).toFloat()
        // Ease between the two rest poses, including the replay's wraparound.
        val poseTime = time % 12_000L
        val attentive = when {
            poseTime < 4_800L -> 0f
            poseTime < 6_000L -> smooth((poseTime - 4_800L) / 1_200f)
            poseTime < 10_800L -> 1f
            else -> 1f - smooth((poseTime - 10_800L) / 1_200f)
        }
        // Uneven, seeded intervals; lids close faster than they reopen.
        val blinkTime = (time % 13_700L + Math.floorMod(input.seed, 1_000L)) % 13_700L
        val blinkAge = when {
            blinkTime >= 9_300L -> blinkTime - 9_300L
            blinkTime >= 4_200L -> blinkTime - 4_200L
            else -> blinkTime
        }
        val openness = when {
            blinkAge < 70L -> 1f - smooth(blinkAge / 70f)
            blinkAge < 180L -> smooth((blinkAge - 70L) / 110f)
            else -> 1f
        }
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
            // Raise the greeting hand into the portrait instead of out past its side.
            envelope * (145f + 5f * sin(age.toDouble() / 150.0).toFloat())
        } else 0f
        return PresenceFrame(
            idle = idle,
            torsoDegrees = -1.6f + attentive * 3.4f + breath * 0.45f,
            headDegrees = 2f - attentive * 4.5f + breath * 0.6f,
            jawDegrees = maxOf(0f, breath) * 0.35f,
            eyeOpenness = openness,
            gazeX = sin((time % 9_000L).toDouble() / 9_000.0 * PI * 2.0 + phase).toFloat() * 0.6f,
            gazeY = sin((time % 7_000L).toDouble() / 7_000.0 * PI * 2.0 + phase).toFloat() * 0.3f,
            gesture = gesture,
            armDegrees = arm,
            clothing = input.clothing,
        )
    }

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
}
