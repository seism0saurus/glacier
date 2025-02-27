package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

import social.bigbone.MastodonClient;

public class MastodonConfigurationTest {

    MastodonConfiguration mastodonConfiguration = new MastodonConfiguration();

    final String instance = "mastodon.example.com";
    final String accessToken = "supersecrettoken";
    final int readTimeout = 240;
    final int writeTimeout = 240;
    final int connectTimeout = 240;

    // Can only test, if the instance name is correctly set.
    // The other parameters are stored internally and can't be checked without using reflections or spys
    // That complexity is not worth testing
    @Test
    void shouldReturnMastodonClientWithCorrectInstance() {
        MastodonClient client = mastodonConfiguration.mastodonClient(instance, accessToken, readTimeout, writeTimeout, connectTimeout);
        assertThat(client.getInstanceName()).isEqualTo(instance);
    }
}