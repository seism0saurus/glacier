package de.seism0saurus.glacier.mastodon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import social.bigbone.MastodonClient;

/**
 * Configuration class for setting up the Mastodon client.
 * This class creates and configures a MastodonClient bean
 * using properties defined in the application's configuration files.
 */
@Configuration
public class MastodonConfiguration {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/ressources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(MastodonConfiguration.class);

    @Bean
    public MastodonClient mastodonClient(@Value("${mastodon.instance}") final String instance,
                                         @Value("${mastodon.https}") final boolean https,
                                         @Value("${mastodon.port}") final int port,
                                         @Value("${mastodon.accessToken}") final String accessToken,
                                         @Value("${mastodon.readTimeout}") final int readTimeout,
                                         @Value("${mastodon.writeTimeout}") final int writeTimeout,
                                         @Value("${mastodon.connectTimeout}") final int connectTimeout,
                                         @Value("${glacier.devmode}") final boolean developmentModeMastodonClient

    ) {
        if (developmentModeMastodonClient) {
            if (https){
                LOGGER.warn("Starting Mastodon configuration in development mode trusting all certificates. This is dangerous!");
                return new MastodonClient.Builder(instance)
                        .accessToken(accessToken)
                        .setReadTimeoutSeconds(readTimeout)
                        .setWriteTimeoutSeconds(writeTimeout)
                        .setConnectTimeoutSeconds(connectTimeout)
                        .withTrustAllCerts()
                        .withPort(port)
                        .debug()
                        .build();
            } else {
                LOGGER.warn("Starting Mastodon configuration in development mode with https disabled. This is dangerous!");
                return new MastodonClient.Builder(instance)
                        .accessToken(accessToken)
                        .setReadTimeoutSeconds(readTimeout)
                        .setWriteTimeoutSeconds(writeTimeout)
                        .setConnectTimeoutSeconds(connectTimeout)
                        .withPort(port)
                        .withHttpsDisabled()
                        .debug()
                        .build();
            }
        } else {
            if (https) {
                LOGGER.info("Starting Mastodon configuration in production mode verifying certificates with the java keystore");
                return new MastodonClient.Builder(instance)
                        .accessToken(accessToken)
                        .setReadTimeoutSeconds(readTimeout)
                        .setWriteTimeoutSeconds(writeTimeout)
                        .setConnectTimeoutSeconds(connectTimeout)
                        .withPort(port)
                        .build();
            } else {
                LOGGER.warn("Starting Mastodon configuration in production with https disabled. This is dangerous!");
                return new MastodonClient.Builder(instance)
                        .accessToken(accessToken)
                        .setReadTimeoutSeconds(readTimeout)
                        .setWriteTimeoutSeconds(writeTimeout)
                        .setConnectTimeoutSeconds(connectTimeout)
                        .withPort(port)
                        .withHttpsDisabled()
                        .build();
            }
        }
    }
}
