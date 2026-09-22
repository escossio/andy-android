package io.github.escossio.andy.features.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class AndyPresenceAvatarTest {
    @Test
    fun neutralOrientationProducesNeutralMotion() {
        val motion = andyPresenceMotionFromOrientation(
            pitchDeltaRad = 0f,
            rollDeltaRad = 0f,
        )

        assertEquals(0f, motion.horizontal, 0.0001f)
        assertEquals(0f, motion.vertical, 0.0001f)
    }

    @Test
    fun smallOrientationDeltaMapsToSubtleMotion() {
        val motion = andyPresenceMotionFromOrientation(
            pitchDeltaRad = 0.105f,
            rollDeltaRad = 0.21f,
        )

        assertEquals(0.5f, motion.horizontal, 0.001f)
        assertEquals(-0.25f, motion.vertical, 0.001f)
    }
    @Test
    fun extremeOrientationDeltaIsClamped() {
        val motion = andyPresenceMotionFromOrientation(
            pitchDeltaRad = -10f,
            rollDeltaRad = 10f,
        )

        assertEquals(1f, motion.horizontal, 0.0001f)
        assertEquals(1f, motion.vertical, 0.0001f)
    }
}
