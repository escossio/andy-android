package io.github.escossio.andy

import java.time.Instant

/** Observable device positions, not proof of a person being somewhere. */
internal object LocationContextCollectionPolicy {
    const val SAMPLE_INTERVAL_MILLIS = 60_000L
    const val POSITION_TIMEOUT_MILLIS = 12_000L
    const val SESSION_RENEWAL_LEAD_SECONDS = 120L

    fun shouldRenewSession(expiresAt: Instant, now: Instant): Boolean =
        !expiresAt.isAfter(now.plusSeconds(SESSION_RENEWAL_LEAD_SECONDS))

    fun maySample(
        userEnabled: Boolean,
        locationPermission: Boolean,
        notificationsVisible: Boolean,
        sessionValid: Boolean,
    ): Boolean = userEnabled && locationPermission && notificationsVisible && sessionValid
}
