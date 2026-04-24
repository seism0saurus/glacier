package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for share-specific {@link LogScrubber} extensions.
 *
 * <p>Security requirement: SR-SHARE-17 (no raw share IDs in logs).
 * References: glacier-structured-logging-logback skill, D-13, SR-8.
 */
class LogScrubberShareTest {

    @Test
    void hash8ShareLinkIdProducesEightHexChars() {
        String result = LogScrubber.hash8("sv_someShareLinkId123456789abcdef");
        assertThat(result).hasSize(8);
        assertThat(result).matches("[0-9a-f]{8}");
    }

    @Test
    void hash8ShareViewerIdProducesEightHexChars() {
        String result = LogScrubber.hash8("sv_viewerId123456789abcdef");
        assertThat(result).hasSize(8);
        assertThat(result).matches("[0-9a-f]{8}");
    }

    @Test
    void hash8IsDeterministic() {
        String id = "sv_testId123456789";
        assertThat(LogScrubber.hash8(id)).isEqualTo(LogScrubber.hash8(id));
    }

    @Test
    void hash8DifferentInputsProduceDifferentOutput() {
        assertThat(LogScrubber.hash8("sv_id1")).isNotEqualTo(LogScrubber.hash8("sv_id2"));
    }

    @Test
    void hash8NullReturnsNullString() {
        assertThat(LogScrubber.hash8(null)).isEqualTo("null");
    }

    @Test
    void hash8BlankReturnsBlankString() {
        assertThat(LogScrubber.hash8("")).isEqualTo("blank");
    }

    @Test
    void maskIpForLogging() {
        // Verify the IP masker works for share audit logging
        String masked = LogScrubber.maskIp("192.168.1.100");
        assertThat(masked).doesNotContain("100");
        assertThat(masked).contains("192.168.1");
    }

    @Test
    void maskIpv6ForLogging() {
        String masked = LogScrubber.maskIp("2001:db8::1");
        assertThat(masked).contains("xxxx");
    }
}
