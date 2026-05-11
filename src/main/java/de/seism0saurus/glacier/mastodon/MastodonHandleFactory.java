package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.MastodonProperties;
import de.seism0saurus.glacier.util.LogScrubber;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Spring factory component that produces the {@link MastodonShortHandle} bean from the
 * configured {@code mastodon.handle} property (ADR-P3A-2).
 *
 * <p>This class bridges the configuration layer (raw string property) and the domain layer
 * ({@link MastodonShortHandle} value object). The factory eagerly validates the handle at
 * application startup so that misconfiguration is detected before the first WebSocket
 * subscription attempt, rather than at the first use of the handle.
 *
 * <p>Fail-fast contract: if {@code mastodon.handle} is absent, blank, or violates any
 * {@link MastodonShortHandle#parse(String)} rule, the factory throws an
 * {@link IllegalStateException} which prevents the Spring context from starting.
 * The error message uses {@link LogScrubber#forErrorMessage(String)} to prevent the raw
 * handle value from appearing in logs or SIEM alerts (D-13 / SR-8 / ADR-P3A-7).
 *
 * <p>The handle value is obtained from the {@link MastodonProperties} bean via
 * {@link MastodonProperties#getHandle()}. {@code MastodonProperties} is itself a
 * {@code @Validated @ConfigurationProperties} bean that enforces the handle pattern
 * at binding time (ADR-P3A-1, ADR-P3A-2). Injecting it here means handle validation
 * occurs at two layers: the binding constraint on {@code MastodonProperties} and the
 * {@link MastodonShortHandle#parse(String)} domain-rule check in this factory.
 *
 * <p>Security: the factory delegates all validation to {@link MastodonShortHandle#parse(String)},
 * which rejects null, blank, handles with no {@code @} separator, multiple {@code @} separators,
 * control characters, Unicode directional overrides, and handles over 254 characters.
 *
 * <p>Bounded context: Mastodon provisioning / configuration startup.
 * ADR references: ADR-P3A-1 (properties bean ownership), ADR-P3A-2 (factory pattern),
 * SR-P3A-01 (error messages must be scrubbed).
 */
@Component
public class MastodonHandleFactory {

    /**
     * The validated Mastodon configuration bean.
     *
     * <p>Injected so that the full {@link MastodonShortHandle#parse(String)} validation
     * suite runs at Spring context startup time — before any STOMP subscription is attempted.
     * Using the properties bean rather than a raw {@code @Value} string ensures that the
     * double-validation invariant is preserved: {@code MastodonProperties} enforces the
     * {@code @Pattern} constraint at binding time; this factory enforces the domain rules
     * at bean-creation time (ADR-P3A-1, ADR-P3A-2).
     */
    private final MastodonProperties mastodonProperties;

    /**
     * Constructs the factory with the validated Mastodon configuration bean.
     *
     * @param mastodonProperties the validated properties bean that carries the raw handle string;
     *                           must not be {@code null} — Spring guarantees a non-null bean
     *                           reference here because {@code MastodonProperties} is a
     *                           {@code @Component}
     */
    public MastodonHandleFactory(final MastodonProperties mastodonProperties) {
        this.mastodonProperties = mastodonProperties;
    }

    /**
     * Produces the {@link MastodonShortHandle} bean by parsing and validating the configured
     * {@code mastodon.handle} property.
     *
     * <p>This method is called exactly once during Spring context initialisation.
     * Any {@link IllegalArgumentException} or {@link NullPointerException} thrown by
     * {@link MastodonShortHandle#parse(String)} is wrapped in an {@link IllegalStateException}
     * with a scrubbed error message that does NOT echo the raw value.
     *
     * @return a validated {@link MastodonShortHandle} ready for injection into consumers
     * @throws IllegalStateException if the configured handle is not a valid Mastodon handle;
     *                               the message uses {@link LogScrubber#forErrorMessage} so
     *                               no raw value is included (D-13 / SR-8 / ADR-P3A-7)
     */
    @Bean
    public MastodonShortHandle mastodonShortHandle() {
        String rawHandle = mastodonProperties.getHandle();
        try {
            return MastodonShortHandle.parse(rawHandle);
        } catch (NullPointerException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "mastodon.handle is not a valid Mastodon handle: "
                            + LogScrubber.forErrorMessage(rawHandle),
                    ex);
        }
    }
}
