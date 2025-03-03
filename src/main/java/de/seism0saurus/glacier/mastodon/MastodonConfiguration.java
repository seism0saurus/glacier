package de.seism0saurus.glacier.mastodon;

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

    @Bean
    public MastodonClient mastodonClient(@Value("${mastodon.instance}") final String instance,
                                         @Value("${mastodon.accessToken}") final String accessToken,
                                         @Value("${mastodon.readTimeout}") final int readTimeout,
                                         @Value("${mastodon.writeTimeout}") final int writeTimeout,
                                         @Value("${mastodon.connectTimeout}") final int connectTimeout,
                                         @Value("${glacier.trustAllCerts}") final boolean trusAllCerts

    ) {
        if (trusAllCerts) {
            return new MastodonClient.Builder(instance)
                    .accessToken(accessToken)
                    .setReadTimeoutSeconds(readTimeout)
                    .setWriteTimeoutSeconds(writeTimeout)
                    .setConnectTimeoutSeconds(connectTimeout)
                    .withTrustAllCerts()
                    .build();
        } else {
            return new MastodonClient.Builder(instance)
                    .accessToken(accessToken)
                    .setReadTimeoutSeconds(readTimeout)
                    .setWriteTimeoutSeconds(writeTimeout)
                    .setConnectTimeoutSeconds(connectTimeout)
                    .build();
        }
    }
}
