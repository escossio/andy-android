package io.github.escossio.andy.features.presence

import org.junit.Assert.*
import org.junit.Test

class PresenceReplayTest {
    @Test fun sameSeedAndExplicitTimeAreOrderIndependent() {
        val times = listOf(0L, 100L, 6000L, 12000L, Long.MAX_VALUE)
        val expected = times.associateWith { PresenceReplay.frame(PresenceInput(seed = -91, elapsedMillis = it)) }
        times.reversed().forEach { assertEquals(expected[it], PresenceReplay.frame(PresenceInput(-91, it))) }
        assertNotEquals(PresenceReplay.frame(PresenceInput(1, 700)), PresenceReplay.frame(PresenceInput(2, 700)))
    }

    @Test fun switchesBetweenTwoRestPoses() {
        val rest = PresenceReplay.frame(PresenceInput(elapsedMillis = 5999))
        val attentive = PresenceReplay.frame(PresenceInput(elapsedMillis = 6000))
        assertEquals(PresenceIdle.REST, rest.idle)
        assertEquals(PresenceIdle.ATTENTIVE, attentive.idle)
        assertNotEquals(rest.torsoDegrees, attentive.torsoDegrees)
        assertEquals(PresenceIdle.REST, PresenceReplay.frame(PresenceInput(elapsedMillis = 12000)).idle)
    }

    @Test fun blinkClosesAndReopens() {
        assertEquals(1f, PresenceReplay.frame(PresenceInput(0, 0)).eyeOpenness)
        assertEquals(0f, PresenceReplay.frame(PresenceInput(0, 70)).eyeOpenness)
        assertEquals(1f, PresenceReplay.frame(PresenceInput(0, 180)).eyeOpenness)
        for (time in listOf(4_270L, 9_370L, 13_770L)) {
            assertEquals(0f, PresenceReplay.frame(PresenceInput(0, time)).eyeOpenness)
        }
        assertEquals(1f, PresenceReplay.frame(PresenceInput(0, 4_100)).eyeOpenness)
    }

    @Test fun restPoseTransitionsAndCycleWrapsDoNotSnap() {
        for (time in listOf(4_800L, 6_000L, 8_000L, 10_800L, 12_000L, 24_000L)) {
            val before = PresenceReplay.frame(PresenceInput(elapsedMillis = time - 1))
            val after = PresenceReplay.frame(PresenceInput(elapsedMillis = time))
            assertEquals(before.torsoDegrees, after.torsoDegrees, 0.002f)
            assertEquals(before.headDegrees, after.headDegrees, 0.002f)
            assertEquals(before.jawDegrees, after.jawDegrees, 0.002f)
        }
    }

    @Test fun idleMovementStaysSubtleAcrossSeedsAndLongReplay() {
        for (seed in listOf(Long.MIN_VALUE, -1L, 0L, 7L, Long.MAX_VALUE)) {
            for (time in 0L..60_000L step 37L) {
                val frame = PresenceReplay.frame(PresenceInput(seed, time))
                assertTrue(frame.torsoDegrees in -2.1f..2.3f)
                assertTrue(frame.headDegrees in -3.1f..2.6f)
                assertTrue(frame.jawDegrees in 0f..0.35f)
                assertTrue(frame.eyeOpenness in 0f..1f)
            }
        }
    }

    @Test fun gazeAndJawAreSyntheticAndBounded() {
        val frames = (0L..18000L step 100L).map { PresenceReplay.frame(PresenceInput(19, it)) }
        assertTrue(frames.map { it.gazeX }.distinct().size > 10)
        assertTrue(frames.map { it.gazeY }.distinct().size > 10)
        frames.forEach {
            assertTrue(it.gazeX in -1f..1f)
            assertTrue(it.gazeY in -0.5f..0.5f)
            assertTrue(it.jawDegrees in 0f..3f)
            assertTrue(it.eyeOpenness in 0f..1f)
        }
    }

    @Test fun gestureStartsCancelsAndCanRestart() {
        val input = PresenceInput(elapsedMillis = 1400, gestureStartedAtMillis = 1000)
        assertEquals(PresenceGesture.REST, PresenceReplay.frame(input.copy(elapsedMillis = 999)).gesture)
        assertEquals(PresenceGesture.WAVING, PresenceReplay.frame(input).gesture)
        assertTrue(PresenceReplay.frame(input).armDegrees > 0f)
        val cancelled = input.copy(gestureCancelledAtMillis = 1300)
        assertEquals(PresenceGesture.WAVING, PresenceReplay.frame(cancelled.copy(elapsedMillis = 1299)).gesture)
        assertEquals(PresenceGesture.INTERRUPTED, PresenceReplay.frame(cancelled).gesture)
        assertEquals(0f, PresenceReplay.frame(cancelled).armDegrees)
        assertEquals(PresenceGesture.WAVING, PresenceReplay.frame(cancelled.copy(
            elapsedMillis = 1900, gestureStartedAtMillis = 1500,
        )).gesture)
        assertEquals(PresenceGesture.FINISHED, PresenceReplay.frame(input.copy(elapsedMillis = 3400)).gesture)
        assertEquals(PresenceGesture.FINISHED, PresenceReplay.frame(input.copy(
            elapsedMillis = 4000, gestureCancelledAtMillis = 3800,
        )).gesture)
    }

    @Test fun clothingVariantDoesNotChangeMotion() {
        val jade = PresenceReplay.frame(PresenceInput(elapsedMillis = 1700))
        val plum = PresenceReplay.frame(PresenceInput(elapsedMillis = 1700, clothing = PresenceClothing.PLUM))
        assertEquals(PresenceClothing.PLUM, plum.clothing)
        assertEquals(jade.copy(clothing = PresenceClothing.PLUM), plum)
    }

    @Test fun greetingHoldsTheHandInsideThePortraitPoseRange() {
        for (age in 350L..2_050L step 17L) {
            val frame = PresenceReplay.frame(PresenceInput(
                elapsedMillis = 1_000L + age,
                gestureStartedAtMillis = 1_000L,
            ))
            assertEquals(PresenceGesture.WAVING, frame.gesture)
            assertTrue(frame.armDegrees in 140f..150f)
        }
        assertEquals(0f, PresenceReplay.frame(PresenceInput(
            elapsedMillis = 1_000L, gestureStartedAtMillis = 1_000L,
        )).armDegrees)
        assertEquals(0f, PresenceReplay.frame(PresenceInput(
            elapsedMillis = 3_400L, gestureStartedAtMillis = 1_000L,
        )).armDegrees)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeTime() { PresenceInput(elapsedMillis = -1) }
}
