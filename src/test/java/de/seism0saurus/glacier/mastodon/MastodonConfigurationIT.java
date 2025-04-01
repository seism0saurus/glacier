package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.EnableWireMock;
import social.bigbone.MastodonClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = MastodonConfigurationIT.TestConfiguration.class,
        properties = "spring.main.allow-bean-definition-overriding=true"
)
@EnableWireMock
public class MastodonConfigurationIT {

    @Value("${wiremock.server.port}")
    private int port;
    final String accessToken = "supersecrettoken";
    final int readTimeout = 10;
    final int writeTimeout = 10;
    final int connectTimeout = 10;

    @Test
    void shouldReturnMastodonClientWithCorrectInstance() {
        // Arange: WireMock
        //noinspection HttpUrlsUsage
        stubFor(get(urlPathMatching("/.well-known/nodeinfo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"links\":[{\"rel\":\"http://nodeinfo.diaspora.software/ns/schema/2.0\",\"href\":\"http://localhost:" + port + "/nodeinfo/2.0\"}]}")
                )
        );
        stubFor(get(urlPathMatching("/nodeinfo/2.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"2.0\",\"software\":{\"name\":\"mastodon\",\"version\":\"4.3.3\"},\"protocols\":[\"activitypub\"],\"services\":{\"outbound\":[],\"inbound\":[]},\"usage\":{\"users\":{\"total\":2,\"activeMonth\":6,\"activeHalfyear\":6},\"localPosts\":1337},\"openRegistrations\":false,\"metadata\":{\"nodeName\":\"localhost\",\"nodeDescription\":\"localhost. Just a WireMock version of Mastodon\"}}")
                )
        );
        stubFor(get(urlPathMatching("/api/v2/instance"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"domain\":\"localhost\",\"title\":\"WireMockMastodon\",\"version\":\"4.3.3\",\"source_url\":\"https://github.com/mastodon/mastodon\",\"description\":\"localhost. Just a WireMock version of Mastodon\",\"usage\":{\"users\":{\"active_month\":6}},\"thumbnail\":{\"url\":\"http://localhost/thumbnail.png\"},\"icon\":[{\"src\":\"http://localhost/icon.png\",\"size\":\"512x512\"}],\"languages\":[\"en\"],\"configuration\":{\"urls\":{\"streaming\":\"ws://localhost:" + port + "\",\"status\":\"\"},\"vapid\":{\"public_key\":\"secret_vapid_key\"},\"accounts\":{\"max_featured_tags\":10,\"max_pinned_statuses\":5},\"statuses\":{\"max_characters\":500,\"max_media_attachments\":4,\"characters_reserved_per_url\":23},\"media_attachments\":{\"supported_mime_types\":[\"image/jpeg\",\"image/png\",\"image/gif\",\"image/heic\",\"image/heif\",\"image/webp\",\"image/avif\",\"video/webm\",\"video/mp4\",\"video/quicktime\",\"video/ogg\",\"audio/wave\",\"audio/wav\",\"audio/x-wav\",\"audio/x-pn-wave\",\"audio/vnd.wave\",\"audio/ogg\",\"audio/vorbis\",\"audio/mpeg\",\"audio/mp3\",\"audio/webm\",\"audio/flac\",\"audio/aac\",\"audio/m4a\",\"audio/x-m4a\",\"audio/mp4\",\"audio/3gpp\",\"video/x-ms-asf\"],\"image_size_limit\":16777216,\"image_matrix_limit\":33177600,\"video_size_limit\":103809024,\"video_frame_rate_limit\":120,\"video_matrix_limit\":8294400},\"polls\":{\"max_options\":4,\"max_characters_per_option\":50,\"min_expiration\":300,\"max_expiration\":2629746}},\"api_versions\":{\"mastodon\":2}}")
                )
        );

        MastodonConfiguration mastodonConfiguration = new MastodonConfiguration();

        // Act: Create MastodonClient against WireMock server
        MastodonClient client = mastodonConfiguration.mastodonClient(
                "localhost", false, port, accessToken, readTimeout, writeTimeout, connectTimeout, true
        );

        // Assert: Client properties
        assertThat(client.getInstanceName()).isEqualTo("localhost");
        assertThat(client.getPort()).isEqualTo(port);
        assertThat(client.getScheme()).isEqualTo("http");
        assertThat(client.getInstanceVersion()).isEqualTo("4.3.3");
    }

    static class TestConfiguration {
    }
}