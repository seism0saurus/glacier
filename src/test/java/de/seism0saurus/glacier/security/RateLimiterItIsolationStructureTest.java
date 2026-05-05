package de.seism0saurus.glacier.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural gate test that verifies {@code @DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)}
 * is present on every Bucket4j rate-limiter IT class.
 *
 * <h2>Why this test exists</h2>
 * <p>The three integration tests listed below exercise Bucket4j token buckets through the real
 * Spring context.  Because Bucket4j holds in-memory per-IP rate-limit state as singletons, bucket
 * state from one test method can bleed into the next method in the same JVM run.  The annotation
 * {@code @DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)} instructs the Spring
 * TestContext framework to reset (close and reopen) the application context after every
 * {@code @Test} method, thereby discarding all singleton state — including the Bucket4j token
 * buckets — before the next method begins.
 *
 * <h2>Regression protection</h2>
 * <p>This test is a pure Surefire unit test (class name ends in {@code Test}, not {@code IT}).
 * It therefore runs even when integration tests are skipped via {@code -P SkipIntegrationTest}.
 * The structural assertion will catch any future refactor that accidentally removes or weakens
 * the {@code @DirtiesContext} annotation before the developer runs the full {@code mvn verify}
 * suite and discovers the isolation regression at runtime.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>{@link SubscribeRateLimitProductionPathIT} — per-IP subscribe rate limit on
 *       {@code /websocket} (IT-sec-RL-01, SR-WS-02)</li>
 *   <li>{@link ShareViewRemoteAddrProductionPathIT} — per-IP subscribe rate limit on
 *       {@code /share-view-ws} (IT-sec-SV-RL-01, SR-OBS1-03)</li>
 *   <li>{@link HandshakeForwardedForRespectedIT} — handshake rate limit under
 *       {@code FRAMEWORK} forward-headers strategy (IT-sec-FH-01, SR-WS-03)</li>
 * </ul>
 *
 * <p>Standard: SR-RL-01 / SR-RL-02.
 */
class RateLimiterItIsolationStructureTest {

    /**
     * SR-RL-01 / SR-RL-02: {@link SubscribeRateLimitProductionPathIT} must carry
     * {@code @DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)}.
     *
     * <p>The annotation ensures that the Bucket4j per-IP token bucket accumulated during one
     * {@code @Test} method does not carry over into the next, preventing false failures when
     * the IP that the test connects from is already rate-limited from a prior method run.
     */
    @Test
    @DisplayName("SR-RL-01: SubscribeRateLimitProductionPathIT carries @DirtiesContext(AFTER_EACH_TEST_METHOD)")
    void subscribeRateLimitIt_hasDirtiesContextAfterEachTestMethod() {
        DirtiesContext annotation = SubscribeRateLimitProductionPathIT.class
                .getAnnotation(DirtiesContext.class);

        assertThat(annotation)
                .as("SR-RL-01: @DirtiesContext must be present on SubscribeRateLimitProductionPathIT "
                        + "to reset Bucket4j token buckets between @Test methods")
                .isNotNull();

        assertThat(annotation.classMode())
                .as("SR-RL-01: @DirtiesContext.classMode must be AFTER_EACH_TEST_METHOD so that "
                        + "bucket state is discarded after every individual test method, "
                        + "not just after the whole class")
                .isEqualTo(ClassMode.AFTER_EACH_TEST_METHOD);
    }

    /**
     * SR-RL-01 / SR-RL-02: {@link ShareViewRemoteAddrProductionPathIT} must carry
     * {@code @DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)}.
     *
     * <p>The share-view WebSocket path uses the same {@code SubscribeRateLimitInterceptor}
     * Bucket4j instance as the main wall path; without context isolation the per-IP bucket
     * populated during one test method is still exhausted when the next method runs, causing
     * spurious rate-limit events even at the first SUBSCRIBE frame of a fresh connection.
     */
    @Test
    @DisplayName("SR-RL-01: ShareViewRemoteAddrProductionPathIT carries @DirtiesContext(AFTER_EACH_TEST_METHOD)")
    void shareViewRemoteAddrIt_hasDirtiesContextAfterEachTestMethod() {
        DirtiesContext annotation = ShareViewRemoteAddrProductionPathIT.class
                .getAnnotation(DirtiesContext.class);

        assertThat(annotation)
                .as("SR-RL-01: @DirtiesContext must be present on ShareViewRemoteAddrProductionPathIT "
                        + "to reset Bucket4j token buckets between @Test methods")
                .isNotNull();

        assertThat(annotation.classMode())
                .as("SR-RL-01: @DirtiesContext.classMode must be AFTER_EACH_TEST_METHOD so that "
                        + "bucket state is discarded after every individual test method")
                .isEqualTo(ClassMode.AFTER_EACH_TEST_METHOD);
    }

    /**
     * SR-RL-01 / SR-RL-02: {@link HandshakeForwardedForRespectedIT} must carry
     * {@code @DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)}.
     *
     * <p>This IT uses a capacity-1 bucket for the WebSocket handshake rate limiter.  Without
     * context reset, a prior test method that consumed the single token would leave subsequent
     * methods with an already-exhausted bucket, making the "first call must be allowed" assertion
     * fail non-deterministically.
     */
    @Test
    @DisplayName("SR-RL-01: HandshakeForwardedForRespectedIT carries @DirtiesContext(AFTER_EACH_TEST_METHOD)")
    void handshakeForwardedForIt_hasDirtiesContextAfterEachTestMethod() {
        DirtiesContext annotation = HandshakeForwardedForRespectedIT.class
                .getAnnotation(DirtiesContext.class);

        assertThat(annotation)
                .as("SR-RL-01: @DirtiesContext must be present on HandshakeForwardedForRespectedIT "
                        + "to reset Bucket4j token buckets between @Test methods")
                .isNotNull();

        assertThat(annotation.classMode())
                .as("SR-RL-01: @DirtiesContext.classMode must be AFTER_EACH_TEST_METHOD so that "
                        + "bucket state is discarded after every individual test method")
                .isEqualTo(ClassMode.AFTER_EACH_TEST_METHOD);
    }
}
