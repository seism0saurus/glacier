package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;

/**
 * Domain event emitted by {@link ShareLinkServiceImpl} after a new share link is
 * successfully persisted.
 *
 * <p>Carries only the minimum data required for routing: the sharer's wallId and
 * the raw share-link ID (SR-RELAY-11). Listeners use these two fields to populate
 * {@link ShareLinkActivityRegistry} and to emit AUDIT log entries.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Constructor is package-private — only {@link ShareLinkServiceImpl} (same package)
 *       may emit this event (ARCH-RELAY-03).</li>
 *   <li>No full aggregate is carried: the raw token is available as
 *       {@link ShareLinkId#value()} but must never be logged (use {@link ShareLinkId#hash8()}).</li>
 * </ul>
 *
 * @param sharerWallId the wallId of the sharer who owns the newly created link
 * @param shareLinkId  the raw share-link ID; used to build STOMP topic paths
 */
record ShareLinkActivatedEvent(String sharerWallId, ShareLinkId shareLinkId) {

    /**
     * Package-private constructor enforces that only {@link ShareLinkServiceImpl} may
     * construct this event (ARCH-RELAY-03). Spring's {@code ApplicationEventPublisher}
     * accepts any object — restricting the constructor to package-private is the
     * compiler-enforced gate.
     */
    ShareLinkActivatedEvent {
        // compact record constructor — validation only
        if (sharerWallId == null || sharerWallId.isBlank()) {
            throw new IllegalArgumentException("sharerWallId must not be null or blank");
        }
        if (shareLinkId == null) {
            throw new IllegalArgumentException("shareLinkId must not be null");
        }
    }
}
