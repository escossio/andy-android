package io.github.escossio.andy.features.presence

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class PresenceStageTest {
    @Test fun fallbackCoversDisableLoadingFailureAndUnavailable() {
        PresenceAvailability.entries.forEach {
            assertTrue(presenceUsesFallback(false, it))
            assertEquals(it != PresenceAvailability.AVAILABLE, presenceUsesFallback(true, it))
        }
    }

    @Test fun lifetimeReleasesAndDoesNotResurrect() {
        val lifetime = PresenceLifetime()
        assertEquals(PresenceAvailability.LOADING, lifetime.availability)
        lifetime.ready()
        assertEquals(PresenceAvailability.AVAILABLE, lifetime.availability)
        lifetime.release()
        lifetime.ready()
        lifetime.fail()
        lifetime.release()
        assertEquals(PresenceAvailability.RELEASED, lifetime.availability)
    }

    @Test fun failureIsTerminalUntilNewLifetime() {
        for (unavailable in listOf(false, true)) {
            val lifetime = PresenceLifetime()
            lifetime.fail(unavailable)
            val expected = if (unavailable) PresenceAvailability.UNAVAILABLE else PresenceAvailability.FAILED
            lifetime.ready()
            assertEquals(expected, lifetime.availability)
            assertTrue(presenceUsesFallback(true, lifetime.availability))
            assertEquals(PresenceAvailability.LOADING, PresenceLifetime().availability)
        }
    }

    @Test fun inputsAndFramesOnlyContainImmutableVisualValues() {
        val permitted = setOf(
            java.lang.Long.TYPE, java.lang.Long::class.java, java.lang.Float.TYPE,
            PresenceClothing::class.java, PresenceIdle::class.java, PresenceGesture::class.java,
        )
        for (type in listOf(PresenceInput::class.java, PresenceFrame::class.java)) {
            for (field in type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }) {
                assertTrue("Immutable visual field: ${field.name}", Modifier.isFinal(field.modifiers))
                assertTrue("Visual value type: ${field.name}", field.type in permitted)
            }
        }
    }
}
