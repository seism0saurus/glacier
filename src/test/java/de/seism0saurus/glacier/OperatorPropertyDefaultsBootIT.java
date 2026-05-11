package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the DEFAULT operator values from
 * {@code application.properties} pass all validation constraints.
 *
 * <p>This is a safety net: if overzealous regex tightening breaks the default
 * boot, this test fails immediately (SR-P3A-15, OperatorPropertyDefaultsBootIT
 * requirement from the test plan).
 *
 * <p>Default values under test (from src/test/resources/application.properties):
 * <ul>
 *   <li>name: Jon Doe</li>
 *   <li>streetAndNumber: somewhere 1</li>
 *   <li>zipcode: 12345</li>
 *   <li>city: somecity</li>
 *   <li>country: Germany</li>
 *   <li>phone: +123456789</li>
 *   <li>mail: mail@example.com</li>
 *   <li>website: example.com</li>
 * </ul>
 */
@SpringBootTest
class OperatorPropertyDefaultsBootIT {

    @Autowired
    private GlacierOperatorProperties operatorProperties;

    /** Prevent actual Bigbone connection attempts during context startup. */
    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    @Test
    void contextStartsCleanly_withDefaultOperatorValues() {
        // The mere fact that this test gets here means the context started successfully
        assertThat(operatorProperties)
                .as("GlacierOperatorProperties must be present in context with default values")
                .isNotNull();
    }

    @Test
    void defaultOperatorValues_areNonBlank() {
        assertThat(operatorProperties.getName())
                .as("default operator name must be present")
                .isNotBlank();
        assertThat(operatorProperties.getMail())
                .as("default operator mail must be present")
                .isNotBlank();
        assertThat(operatorProperties.getWebsite())
                .as("default operator website must be present")
                .isNotBlank();
    }
}
