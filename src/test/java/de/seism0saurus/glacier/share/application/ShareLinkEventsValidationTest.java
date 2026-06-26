package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compact-constructor validation for the relay lifecycle events.
 *
 * <p>{@link ShareLinkActivatedEvent} and {@link ShareLinkRevokedEvent} have package-private
 * constructors (ARCH-RELAY-03 restricts production construction to {@link ShareLinkServiceImpl});
 * this test lives in the same package so it can exercise the fail-fast guards directly. The
 * ArchUnit gate scans production classes only ({@code DO_NOT_INCLUDE_TESTS}), so constructing the
 * events here does not violate ARCH-RELAY-03.
 */
class ShareLinkEventsValidationTest {

    private static final ShareLinkId ID =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @Test
    void activatedEvent_rejectsNullOrBlankWallId_andNullId() {
        assertThatThrownBy(() -> new ShareLinkActivatedEvent(null, ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkActivatedEvent("  ", ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkActivatedEvent("wall-id", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokedEvent_rejectsNullOrBlankWallId_andNullId() {
        assertThatThrownBy(() -> new ShareLinkRevokedEvent(null, ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkRevokedEvent("  ", ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkRevokedEvent("wall-id", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothEvents_acceptValidArguments() {
        // Sanity: a valid (wallId, id) pair constructs without throwing.
        new ShareLinkActivatedEvent("wall-id", ID);
        new ShareLinkRevokedEvent("wall-id", ID);
    }
}
