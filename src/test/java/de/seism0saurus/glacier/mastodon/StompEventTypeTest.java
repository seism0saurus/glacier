package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the STOMP wire-suffix contract for {@link StompEventType} (SR-F6INFO2-07).
 * Any rename of an enum constant without updating the suffix string will fail here.
 */
class StompEventTypeTest {

    @Test
    void creation_suffix_returnsCreation() {
        assertThat(StompEventType.CREATION.suffix()).isEqualTo("creation");
    }

    @Test
    void modification_suffix_returnsModification() {
        assertThat(StompEventType.MODIFICATION.suffix()).isEqualTo("modification");
    }

    @Test
    void deletion_suffix_returnsDeletion() {
        assertThat(StompEventType.DELETION.suffix()).isEqualTo("deletion");
    }
}
