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
 * ImageProxyHmacSecretValidator fails-closed in production (glacier.cookie.secure=true).
 * SR-SHARE-10, user resolution 2026-04-22 option A.
 *
 * <p>Note: glacier.cookie.secure must be set explicitly because {@code GlacierCookieProperties}
 * uses {@code @NotNull Boolean} — no default is provided, and a missing value causes a
 * {@code BindValidationException} at startup (ADR-P3B-1, CWE-1188, SR-P3B-07).
 *
 * <p>Note: spring.datasource.url is required because {@code spring-boot-starter-jdbc}
 * activates {@code DataSourceAutoConfiguration}. An in-memory SQLite URL satisfies the
 * requirement for this context-load smoke test (P3-05, ADR-SQLITE-01).
 */
// SR-SHARE-10: ImageProxyHmacSecretValidator fails-closed in prod; provide a 32-byte+ secret
// ADR-P3B-1: glacier.cookie.secure must be set explicitly (no @Value default)
// P3-05 / ADR-SQLITE-01: in-memory SQLite URL required by DataSourceAutoConfiguration
@SpringBootTest
@TestPropertySource(properties = {
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "glacier.cookie.secure=true",
        "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC"
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