package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import social.bigbone.MastodonClient;

public class MastodonConfigurationTest {

    final String instance = "mastodon.example.com";
    final String accessToken = "supersecrettoken";
    final int readTimeout = 240;
    final int writeTimeout = 240;
    final int connectTimeout = 240;

    final MastodonConfiguration mastodonConfiguration = mock(MastodonConfiguration.class);

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceInDevelopmentModeHTTPSDisabled() {
        // Mock MastodonClient
        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(instance, false, 80, accessToken, readTimeout, writeTimeout, connectTimeout, false))
                .thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        // Act
        MastodonClient client = mastodonConfiguration.mastodonClient(instance, false, 80, accessToken, readTimeout, writeTimeout, connectTimeout, false);

        // Assert
        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(instance,false, 80, accessToken, readTimeout, writeTimeout, connectTimeout, false);
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceInDevelopmentMode() {
        // Mock MastodonClient
        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(instance, true, 443, accessToken, readTimeout, writeTimeout, connectTimeout, false))
                .thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        // Act
        MastodonClient client = mastodonConfiguration.mastodonClient(instance, true, 443, accessToken, readTimeout, writeTimeout, connectTimeout, false);

        // Assert
        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(instance,true, 443, accessToken, readTimeout, writeTimeout, connectTimeout, false);
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceWithCertifcateCheck() {
        // Mock MastodonClient
        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(instance, true, 443,accessToken, readTimeout, writeTimeout, connectTimeout, true))
                .thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        // Act
        MastodonClient client = mastodonConfiguration.mastodonClient(instance, true, 443,accessToken, readTimeout, writeTimeout, connectTimeout, true);

        // Assert
        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(instance, true, 443,accessToken, readTimeout, writeTimeout, connectTimeout, true);
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceWithHTTPSDisabled() {
        // Mock MastodonClient
        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(instance, false, 8080,accessToken, readTimeout, writeTimeout, connectTimeout, true))
                .thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        // Act
        MastodonClient client = mastodonConfiguration.mastodonClient(instance, false, 8080,accessToken, readTimeout, writeTimeout, connectTimeout, true);

        // Assert
        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(instance, false, 8080,accessToken, readTimeout, writeTimeout, connectTimeout, true);
    }

}