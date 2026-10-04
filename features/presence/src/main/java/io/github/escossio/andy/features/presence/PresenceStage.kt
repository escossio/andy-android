package io.github.escossio.andy.features.presence

/** Immutable visual inputs only. Time is relative to the start of this replay. */
data class PresenceInput(
    val seed: Long = 7L,
    val elapsedMillis: Long = 0L,
    val gestureStartedAtMillis: Long? = null,
    val gestureCancelledAtMillis: Long? = null,
    val clothing: PresenceClothing = PresenceClothing.JADE,
) {
    init {
        require(elapsedMillis >= 0L)
        require(gestureStartedAtMillis == null || gestureStartedAtMillis >= 0L)
        require(gestureCancelledAtMillis == null || gestureCancelledAtMillis >= 0L)
    }
}

enum class PresenceClothing { JADE, PLUM }
enum class PresenceIdle { REST, ATTENTIVE }
enum class PresenceGesture { REST, WAVING, INTERRUPTED, FINISHED }
enum class PresenceAvailability { LOADING, AVAILABLE, UNAVAILABLE, FAILED, RELEASED }

data class PresenceFrame(
    val idle: PresenceIdle,
    val torsoDegrees: Float,
    val headDegrees: Float,
    val jawDegrees: Float,
    val eyeOpenness: Float,
    val gazeX: Float,
    val gazeY: Float,
    val gesture: PresenceGesture,
    val armDegrees: Float,
    val clothing: PresenceClothing,
)

/** Loading also shows Canvas while the adapter prepares its first frame. */
fun presenceUsesFallback(enabled: Boolean, availability: PresenceAvailability): Boolean =
    !enabled || availability != PresenceAvailability.AVAILABLE

/** Terminal failures cannot become ready again without a new stage lifetime. */
internal class PresenceLifetime {
    var availability: PresenceAvailability = PresenceAvailability.LOADING
        private set

    fun ready() {
        if (availability == PresenceAvailability.LOADING) availability = PresenceAvailability.AVAILABLE
    }

    fun fail(unavailable: Boolean = false) {
        if (availability == PresenceAvailability.LOADING || availability == PresenceAvailability.AVAILABLE) {
            availability = if (unavailable) PresenceAvailability.UNAVAILABLE else PresenceAvailability.FAILED
        }
    }

    fun release() { availability = PresenceAvailability.RELEASED }
}
