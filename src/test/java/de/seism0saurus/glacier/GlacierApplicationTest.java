package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
     * MastodonClient needs to be mocked because it directly tests the connection to an nonexistent webservice.
     */
    @MockBean
    MastodonClient client;

    @Test
    void contextLoads() {
        // This test will fail if the application context cannot start
        // or if Spring finds that components that should be present are missing.
    }
}