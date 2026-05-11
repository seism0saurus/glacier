package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.MastodonProperties;
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
    final int port = 443;

    final MastodonConfiguration mastodonConfiguration = mock(MastodonConfiguration.class);

    private MastodonProperties buildProps(boolean https) {
        MastodonProperties props = new MastodonProperties();
        props.setInstance(instance);
        props.setAccessToken(accessToken);
        props.setHttps(https);
        props.setPort(port);
        props.setReadTimeout(readTimeout);
        props.setWriteTimeout(writeTimeout);
        props.setConnectTimeout(connectTimeout);
        props.setHandle("glacier@mastodon.example.com");
        return props;
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceInDevelopmentModeHTTPSDisabled() {
        MastodonProperties props = buildProps(false);
        props.setPort(80);

        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(props, false)).thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        MastodonClient client = mastodonConfiguration.mastodonClient(props, false);

        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(props, false);
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceInDevelopmentMode() {
        MastodonProperties props = buildProps(true);

        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(props, false)).thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        MastodonClient client = mastodonConfiguration.mastodonClient(props, false);

        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(props, false);
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceWithCertificateCheck() {
        MastodonProperties props = buildProps(true);

        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(props, true)).thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        MastodonClient client = mastodonConfiguration.mastodonClient(props, true);

        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(props, true);
    }

    @Test
    void shouldReturnMastodonClientWithCorrectInstanceWithHTTPSDisabled() {
        MastodonProperties props = buildProps(false);
        props.setPort(8080);

        MastodonClient mockClient = mock(MastodonClient.class);
        when(mastodonConfiguration.mastodonClient(props, true)).thenReturn(mockClient);
        when(mockClient.getInstanceName()).thenReturn(instance);

        MastodonClient client = mastodonConfiguration.mastodonClient(props, true);

        assertThat(client.getInstanceName()).isEqualTo(instance);
        verify(mastodonConfiguration, times(1)).mastodonClient(props, true);
    }
}
