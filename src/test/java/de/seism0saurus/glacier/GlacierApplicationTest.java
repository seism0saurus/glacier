package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

/**
 * Test class for GlacierApplication.
 * <p>
 * This class contains test methods to ensure the proper functioning of
 * GlacierApplication class.
 */
@SpringBootTest
class GlacierApplicationTests {

    /**
     * MastodonClient needs to be mocked because it directly tests the connection to a nonexistent webservice.
     */
    @MockitoBean
    MastodonClient client;

    @SuppressWarnings("EmptyMethod")
    @Test
    void contextLoads() {
        // This test will fail if the application context cannot start
        // or if Spring finds that components that should be present are missing.
    }
}