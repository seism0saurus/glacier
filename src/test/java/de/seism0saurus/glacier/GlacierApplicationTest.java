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
 * <p>Note: {@code DataSourceAutoConfiguration}, {@code DataSourceTransactionManagerAutoConfiguration},
 * and {@code JdbcTemplateAutoConfiguration} are excluded from {@code @SpringBootApplication}
 * (P3-05; ADR-SQLITE-01). No {@code spring.datasource.*} properties are required — the SQLite
 * DataSource is opt-in via {@code glacier.share.db.path}. {@code glacier.share.db.path} is NOT
 * set here; the in-memory share-link adapter is used for this context-load smoke test.
 */
// SR-SHARE-10: ImageProxyHmacSecretValidator fails-closed in prod; provide a 32-byte+ secret
// ADR-P3B-1: glacier.cookie.secure must be set explicitly (no @Value default)
// P3-05 / ADR-SQLITE-01: DataSource auto-config excluded from @SpringBootApplication; SQLite is opt-in
@SpringBootTest
@TestPropertySource(properties = {
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "glacier.cookie.secure=true"
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