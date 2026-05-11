package de.seism0saurus.glacier;

import de.seism0saurus.glacier.util.LogScrubber;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot {@link ConfigurationPropertiesBindHandlerAdvisor} that prevents
 * raw sensitive configuration values from appearing in bind failure exception messages.
 *
 * <h3>Problem</h3>
 * <p>When {@code @ConfigurationProperties} binding fails, Spring Boot may include the raw
 * rejected value in the exception chain via two distinct paths:
 * <ul>
 *   <li><b>Jakarta Validation constraint failures</b> ({@code @NotBlank}, {@code @Pattern},
 *       {@code @SafeOperatorString}, {@code @MastodonInstanceValidator}) — Spring Boot wraps
 *       the violation in a {@link BindValidationException} whose
 *       {@code ValidationErrors} contains {@code FieldError} objects that expose the raw
 *       rejected value via {@code FieldError.toString()} ("{@code rejected value [<value>]}").
 *       This is the PRIMARY leak path for operator-string and Mastodon config values.</li>
 *   <li><b>Type-conversion failures</b> ({@code BindException}) — the {@code ConfigurationProperty}
 *       attached to the exception carries {@code getValue().toString()} which may include the
 *       raw value (e.g., {@code mastodon.accessToken} if that field fails conversion).</li>
 * </ul>
 * <p>For sensitive properties like {@code mastodon.accessToken}, either path means the token
 * is echoed to stderr and any SIEM log aggregation that captures startup logs
 * (CWE-532: Sensitive Information in Logs; ASVS V7.3.1 L1).
 *
 * <h3>Solution (ADR-P3A-7)</h3>
 * <p>This class registers as a {@link ConfigurationPropertiesBindHandlerAdvisor} bean,
 * which is the Spring Boot-sanctioned mechanism for adding behaviour to the binding
 * handler chain. The advisor wraps the existing handler with a
 * {@link ScrubbingBindHandler} that intercepts {@code onFailure} and wraps any
 * {@link BindException} in a scrubbing wrapper exception.
 *
 * <h3>Registration</h3>
 * <p>{@link ConfigurationPropertiesBindHandlerAdvisor} beans are automatically discovered
 * by {@code ConfigurationPropertiesBindingPostProcessor} — no manual registration is
 * required. The {@code @Bean} method {@link #glacierBindHandlerAdvisor()} is the only
 * registration point.
 *
 * <p>References: ADR-P3A-7; SR-P3A-01 (CRITICAL); AC-P3A-03;
 * CWE-532; ASVS V7.3.1 (L1); C9 — security events must not leak PII/tokens.
 */
@Configuration
public class GlacierBindHandler {

    /**
     * Registers the {@link ScrubbingBindHandler} advisor with Spring Boot's
     * {@code @ConfigurationProperties} binding pipeline.
     *
     * <p>The advisor wraps the existing handler chain, inserting the scrubbing
     * layer at the outermost position so that ALL bind failures pass through it.
     *
     * @return the {@link ConfigurationPropertiesBindHandlerAdvisor}
     */
    @Bean
    public ConfigurationPropertiesBindHandlerAdvisor glacierBindHandlerAdvisor() {
        return ScrubbingBindHandler::new;
    }

    /**
     * An {@link AbstractBindHandler} that intercepts bind failures and wraps them
     * in a scrubbing exception to prevent raw sensitive values from leaking.
     *
     * <p>Two failure paths are handled (SEC-P3A-01, ADR-P3A-7):
     * <ol>
     *   <li><b>{@link BindValidationException}</b> — thrown for Jakarta Validation constraint
     *       violations ({@code @NotBlank}, {@code @Pattern}, {@code @SafeOperatorString}, etc.).
     *       The original exception is dropped entirely (cause = null) because preserving it
     *       as cause would re-expose {@code FieldError.rejectedValue} via
     *       {@code getCause().toString()}. A static count-only message is substituted.</li>
     *   <li><b>{@link BindException}</b> — thrown for type-conversion failures. The raw value
     *       from {@code ConfigurationProperty.getValue().toString()} is replaced with a
     *       {@link LogScrubber#forErrorMessage(String)} summary. The original exception is
     *       preserved as cause because {@code BindException.toString()} alone does not echo
     *       the raw config value without the {@code ConfigurationProperty} being resolved.</li>
     * </ol>
     */
    static class ScrubbingBindHandler extends AbstractBindHandler {

        ScrubbingBindHandler(final BindHandler parent) {
            super(parent);
        }

        /**
         * Intercepts bind failures to replace raw values with scrubbed summaries.
         *
         * <h3>BindValidationException path (Jakarta Validation — PRIMARY leak path)</h3>
         * <p>ADR-P3A-7 / SEC-P3A-01: {@link BindValidationException} is thrown for ALL
         * Jakarta Validation constraint failures ({@code @NotBlank}, {@code @Pattern},
         * {@code @SafeOperatorString}, {@code @MastodonInstanceValidator}).
         * Spring's {@code FieldError.toString()} emits {@code "rejected value [<value>]"},
         * so the raw rejected value is present in {@code bve.toString()} and all
         * {@code FieldError.toString()} calls in its {@code ValidationErrors}.
         *
         * <p><b>CRITICAL:</b> the original exception is dropped (cause = {@code null}).
         * Preserving it as cause re-exposes {@code rejectedValue} to anything calling
         * {@code getCause().toString()} or {@code getCause().getMessage()}.
         * A static count-only message is substituted — no raw value is emitted.
         *
         * <h3>BindException path (type-conversion failures)</h3>
         * <p>When the exception is a {@link BindException} carrying a
         * {@code ConfigurationProperty} with a raw value, the raw value is replaced with
         * a scrubbed summary. The original exception is preserved as cause here because
         * {@code BindException.toString()} alone does not re-echo the raw config value
         * (the value is only in the property object, not in the exception message itself).
         *
         * @param name    the configuration property name being bound
         * @param target  the binding target
         * @param context the bind context
         * @param error   the cause of the binding failure
         * @return never returns normally — always re-throws the (possibly wrapped) exception
         * @throws Exception always thrown; scrubbed wrapper for binding failures
         */
        @Override
        public Object onFailure(
                final ConfigurationPropertyName name,
                final Bindable<?> target,
                final BindContext context,
                final Exception error) throws Exception {

            // BRANCH 1: Jakarta Validation constraint failure — PRIMARY leak path.
            // BindValidationException.toString() includes FieldError.toString() which
            // emits "rejected value [<rawValue>]". Dropping the cause entirely prevents
            // any caller from recovering the raw value via getCause().
            // C9 / CWE-532 / ASVS V7.3.1 L1 / ADR-P3A-7 / SEC-P3A-01
            if (error instanceof BindValidationException bve) {
                int errorCount = bve.getValidationErrors().getAllErrors().size();
                throw new ScrubbedBindException(
                        "Configuration validation failed for '" + name + "' ("
                                + errorCount + " error" + (errorCount == 1 ? "" : "s") + "). "
                                + "Raw rejected values redacted (SR-P3A-01, ADR-P3A-7).",
                        null   // MANDATORY null — preserving bve re-exposes rejectedValue via getCause()
                );
            }

            // BRANCH 2: Type-conversion failure — secondary leak path.
            // BindException.getProperty().getValue() holds the raw value; replace with scrubbed summary.
            if (error instanceof BindException bindException) {
                ConfigurationProperty prop = bindException.getProperty();
                if (prop != null && prop.getValue() != null) {
                    // Replace the raw value with a scrubbed summary in the new exception message
                    // C9 — security events must not leak PII/tokens (ADR-P3A-7, CWE-532, ASVS V7.3.1 L1)
                    String rawValue = prop.getValue().toString();
                    String scrubbed = LogScrubber.forErrorMessage(rawValue);

                    // Throw a scrubbing wrapper — preserving the BindException as cause is safe here
                    // because BindException.toString() alone does not echo the raw config value.
                    throw new ScrubbedBindException(
                            "Failed to bind property '" + name + "': value "
                                    + scrubbed + " (original: see cause)",
                            bindException
                    );
                }
            }

            // Delegate to parent for errors that neither branch above handles
            return super.onFailure(name, target, context, error);
        }
    }

    /**
     * A scrubbing wrapper exception used by both failure branches in
     * {@link ScrubbingBindHandler#onFailure}.
     *
     * <p>For {@link BindValidationException} (Jakarta Validation failures), the cause is
     * {@code null} — mandatory to prevent re-exposure of {@code FieldError.rejectedValue}
     * via {@code getCause().toString()}.
     *
     * <p>For {@link BindException} (type-conversion failures), the original exception is
     * preserved as the cause; the message is replaced with a
     * {@link LogScrubber#forErrorMessage(String)} summary so the raw value is not echoed.
     *
     * <p>ADR-P3A-7; SEC-P3A-01; CWE-532; ASVS V7.3.1 L1.
     */
    static class ScrubbedBindException extends RuntimeException {
        ScrubbedBindException(final String scrubbedMessage, final Throwable cause) {
            super(scrubbedMessage, cause);
        }
    }
}
