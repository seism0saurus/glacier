package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.MastodonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import social.bigbone.MastodonClient;

/**
 * Configuration class for setting up the Mastodon client.
 *
 * <p>Creates and configures a {@link MastodonClient} bean using properties from
 * the application's configuration files.  The client selection uses four
 * mutually exclusive construction paths depending on the combination of
 * {@code glacier.devmode} and {@code mastodon.https} flags (P2-09):
 * <ol>
 *   <li>Dev mode + HTTPS — trusts all certificates (DANGEROUS, logged at WARN).</li>
 *   <li>Dev mode + HTTP — disables HTTPS (DANGEROUS, logged at WARN).</li>
 *   <li>Production + HTTPS — verifies certificates against the JVM trust store (default).</li>
 *   <li>Production + HTTP — disables HTTPS (DANGEROUS, logged at WARN).</li>
 * </ol>
 */
@Configuration
public class MastodonConfiguration {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/resources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(MastodonConfiguration.class);

    /**
     * Creates a {@link MastodonClient} bean configured from application properties.
     *
     * <p>Behaviour is determined by the {@code glacier.devmode} and {@code mastodon.https}
     * flags.  All four combinations are handled by dedicated private builder methods to
     * keep the dispatch logic flat and readable (P2-09 — 4-way nesting refactor).
     *
     * <p>All mastodon.* properties are now injected via the validated
     * {@link MastodonProperties} bean (ADR-P3A-1), which enforces Jakarta Validation
     * constraints at startup and eliminates 8 individual {@code @Value} parameters.
     *
     * @param props                         validated Mastodon connection properties
     * @param developmentModeMastodonClient {@code true} enables unsafe dev-mode overrides
     * @return a fully configured {@link MastodonClient}
     */
    @Bean
    public MastodonClient mastodonClient(
            final MastodonProperties props,
            @Value("${glacier.devmode}") final boolean developmentModeMastodonClient) {

        String instance = props.getInstance();
        boolean https = Boolean.TRUE.equals(props.getHttps());  // ADR-P3A-8: unbox safely
        int port = props.getPort();
        String accessToken = props.getAccessToken();
        int readTimeout = props.getReadTimeout();
        int writeTimeout = props.getWriteTimeout();
        int connectTimeout = props.getConnectTimeout();

        if (developmentModeMastodonClient) {
            return https
                    ? buildDevModeHttpsClient(instance, accessToken, readTimeout, writeTimeout, connectTimeout, port)
                    : buildDevModeHttpClient(instance, accessToken, readTimeout, writeTimeout, connectTimeout, port);
        } else {
            return https
                    ? buildProductionHttpsClient(instance, accessToken, readTimeout, writeTimeout, connectTimeout, port)
                    : buildProductionHttpClient(instance, accessToken, readTimeout, writeTimeout, connectTimeout, port);
        }
    }

    /**
     * Builds a dev-mode client that trusts all TLS certificates.
     *
     * <p>DANGEROUS — intended for local development against self-signed Mastodon instances
     * only.  Never use in production (all certs accepted, MITM possible).
     *
     * @param instance     Mastodon instance hostname
     * @param accessToken  API access token
     * @param readTimeout  read timeout in seconds
     * @param writeTimeout write timeout in seconds
     * @param connectTimeout connect timeout in seconds
     * @param port         API port
     * @return a dev-mode {@link MastodonClient} with trust-all certificate policy
     */
    private MastodonClient buildDevModeHttpsClient(
            String instance, String accessToken,
            int readTimeout, int writeTimeout, int connectTimeout, int port) {
        LOGGER.warn("Starting Mastodon configuration in development mode trusting all certificates. This is dangerous!");
        return baseBuilder(instance, accessToken, readTimeout, writeTimeout, connectTimeout)
                .withTrustAllCerts()
                .withPort(port)
                .debug()
                .build();
    }

    /**
     * Builds a dev-mode client with HTTPS disabled.
     *
     * <p>DANGEROUS — intended for local development against plain-HTTP Mastodon test
     * instances only.  Never use in production (traffic unencrypted).
     *
     * @param instance     Mastodon instance hostname
     * @param accessToken  API access token
     * @param readTimeout  read timeout in seconds
     * @param writeTimeout write timeout in seconds
     * @param connectTimeout connect timeout in seconds
     * @param port         API port
     * @return a dev-mode {@link MastodonClient} with HTTPS disabled
     */
    private MastodonClient buildDevModeHttpClient(
            String instance, String accessToken,
            int readTimeout, int writeTimeout, int connectTimeout, int port) {
        LOGGER.warn("Starting Mastodon configuration in development mode with https disabled. This is dangerous!");
        return baseBuilder(instance, accessToken, readTimeout, writeTimeout, connectTimeout)
                .withPort(port)
                .withHttpsDisabled()
                .debug()
                .build();
    }

    /**
     * Builds a production client that verifies TLS certificates against the JVM trust store.
     *
     * <p>This is the standard production path — certificates are validated through the
     * JVM's default trust store (typically {@code cacerts}).
     *
     * @param instance     Mastodon instance hostname
     * @param accessToken  API access token
     * @param readTimeout  read timeout in seconds
     * @param writeTimeout write timeout in seconds
     * @param connectTimeout connect timeout in seconds
     * @param port         API port
     * @return a production {@link MastodonClient} with certificate verification enabled
     */
    private MastodonClient buildProductionHttpsClient(
            String instance, String accessToken,
            int readTimeout, int writeTimeout, int connectTimeout, int port) {
        LOGGER.info("Starting Mastodon configuration in production mode verifying certificates with the java keystore");
        return baseBuilder(instance, accessToken, readTimeout, writeTimeout, connectTimeout)
                .withPort(port)
                .build();
    }

    /**
     * Builds a production client with HTTPS disabled.
     *
     * <p>DANGEROUS — production use without TLS exposes the access token and all
     * streaming data.  Only acceptable if TLS termination is handled by a trusted
     * reverse-proxy on the same host.
     *
     * @param instance     Mastodon instance hostname
     * @param accessToken  API access token
     * @param readTimeout  read timeout in seconds
     * @param writeTimeout write timeout in seconds
     * @param connectTimeout connect timeout in seconds
     * @param port         API port
     * @return a production {@link MastodonClient} with HTTPS disabled
     */
    private MastodonClient buildProductionHttpClient(
            String instance, String accessToken,
            int readTimeout, int writeTimeout, int connectTimeout, int port) {
        LOGGER.warn("Starting Mastodon configuration in production with https disabled. This is dangerous!");
        return baseBuilder(instance, accessToken, readTimeout, writeTimeout, connectTimeout)
                .withPort(port)
                .withHttpsDisabled()
                .build();
    }

    /**
     * Creates a {@link MastodonClient.Builder} pre-configured with the common parameters
     * shared by all four construction paths: instance, access token, and all three timeout
     * values.
     *
     * <p>Each specific builder method calls this helper first, then appends the flags that
     * differentiate it (e.g. {@code withTrustAllCerts()}, {@code withHttpsDisabled()},
     * or neither for the production HTTPS path).
     *
     * @param instance       Mastodon instance hostname
     * @param accessToken    API access token
     * @param readTimeout    read timeout in seconds
     * @param writeTimeout   write timeout in seconds
     * @param connectTimeout connect timeout in seconds
     * @return a pre-configured builder ready for the caller to apply mode-specific flags
     */
    private static MastodonClient.Builder baseBuilder(
            String instance, String accessToken,
            int readTimeout, int writeTimeout, int connectTimeout) {
        return new MastodonClient.Builder(instance)
                .accessToken(accessToken)
                .setReadTimeoutSeconds(readTimeout)
                .setWriteTimeoutSeconds(writeTimeout)
                .setConnectTimeoutSeconds(connectTimeout);
    }
}
