package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link MastodonProperties} is wired as a
 * Spring bean and populated from the test {@code application.properties}.
 *
 * <p>This test confirms that the {@code @ConfigurationProperties} + {@code @Component}
 * registration works correctly in the full application context, and that the
 * expected field values (from the test application.properties defaults) are present.
 *
 * <p>References: ADR-P3A-1; AC-P3A-02.
 */
@SpringBootTest
class MastodonPropertiesIT {

    @Autowired
    private MastodonProperties mastodonProperties;

    /** Prevent actual Bigbone connection attempts during context startup. */
    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    @Test
    void mastodonPropertiesBean_isPresentInContext() {
        assertThat(mastodonProperties)
                .as("MastodonProperties bean must be present in the Spring context")
                .isNotNull();
    }

    @Test
    void mastodonPropertiesBean_hasExpectedDefaultValues() {
        // Values from src/test/resources/application.properties defaults
        assertThat(mastodonProperties.getInstance())
                .as("mastodon.instance must be populated")
                .isNotBlank();
        assertThat(mastodonProperties.getAccessToken())
                .as("mastodon.accessToken must be populated")
                .isNotBlank();
        assertThat(mastodonProperties.getHttps())
                .as("mastodon.https must be populated (boxed Boolean, ADR-P3A-8)")
                .isNotNull();
        assertThat(mastodonProperties.getPort())
                .as("mastodon.port must be in valid range")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(65535);
        assertThat(mastodonProperties.getReadTimeout())
                .as("mastodon.readTimeout must be at least 1 (SR-P3A-15)")
                .isGreaterThanOrEqualTo(1);
        assertThat(mastodonProperties.getHandle())
                .as("mastodon.handle must be populated")
                .isNotBlank();
    }
}
