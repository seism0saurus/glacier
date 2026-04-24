package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

/**
 * Test class for GlacierApplication.
 * <p>
 * This class contains test methods to ensure the proper functioning of
 * GlacierApplication class.
 *
 * <p>Note: glacier.share.imgproxy.hmacSecret must be provided because
 * ImageProxyHmacSecretValidator fails-closed in production (glacier.cookie.secure=true default).
 * SR-SHARE-10, user resolution 2026-04-22 option A.
 */
// SR-SHARE-10: ImageProxyHmacSecretValidator fails-closed in prod; provide a 32-byte+ secret
@SpringBootTest
@TestPropertySource(properties = {
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
class GlacierApplicationTests {

    /**
     * MastodonClient needs to be mocked because it directly tests the connection to a nonexistent webservice.
     */
    @SuppressWarnings("unused")
    @MockitoBean
    MastodonClient client;

    @SuppressWarnings("EmptyMethod")
    @Test
    void contextLoads() {
        // This test will fail if the application context cannot start
        // or if Spring finds that components that should be present are missing.
    }
}