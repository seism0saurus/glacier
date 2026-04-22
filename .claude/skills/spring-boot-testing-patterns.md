---
name: spring-boot-testing-patterns
owner: "@seism0saurus"
description: Write or modify Spring Boot tests in Glacier's backend with strict separation of unit tests (*Test.java under Surefire) and integration tests (*IT.java under Failsafe), using MockWebServer for bigbone/Mastodon HTTP-level mocking, slice tests (@WebMvcTest) where possible, and @MockitoBean (not deprecated @MockBean). TRIGGER when editing or creating files under src/test/java/**/*Test.java or *IT.java, configuring @SpringBootTest, @WebMvcTest, @MockBean/@MockitoBean, MockMvc, TestRestTemplate, MockWebServer, or when the user mentions unit test, integration test, Surefire, Failsafe, Jacoco, coverage, mock bean, slice test, JUnit, Mockito, @SpringBootTest. SKIP for frontend tests (angular-karma-jasmine-testing or playwright-e2e-patterns), non-test code, or infrastructure-only changes.
---

# Spring Boot Testing Patterns for Glacier

Glacier's test pyramid is non-negotiable (per CLAUDE.md + project memory `feedback_testing_pyramid.md`): every change needs **unit + integration + e2e tests**. `./mvnw verify` enforces Jacoco thresholds: instruction ≥ 45 %, branch ≥ 35 %.

## Test-class naming = test-runner binding (THE most important rule)

| Suffix | Runner | When runs | Purpose |
|---|---|---|---|
| `*Test.java` | **Surefire** | `./mvnw test` (and `./mvnw verify`) | Fast unit tests, no Spring context or slice only |
| `*IT.java` | **Failsafe** | `./mvnw verify` | Integration — full or partial Spring context |

**Claude's most common test-related mistake**: naming an integration test `FooTest.java` → runs under Surefire with wrong phase expectations, OR fails to pick up Failsafe-only config. Always match suffix to test type.

In-repo precedents:
- Unit: `SubscriptionManagerImplTest`, `StompCallbackTest`, `FallbackControllerAdviceTest`, `LogScrubberTest`
- Integration: `MastodonConfigurationIT`, `CorsConfigurationIT`

Skip toggles (both respected in CI and local): `-DskipUnitTests=true`, `-DskipIntegrationTests=true`, or profiles `-P SkipUnitTest`, `-P SkipIntegrationTest`.

## Unit tests — no Spring context by default

Plain JUnit 5 + Mockito:

```java
@ExtendWith(MockitoExtension.class)
class SubscriptionManagerImplTest {
  @Mock MessageCache cache;
  @Mock MastodonClient mastodon;
  @InjectMocks SubscriptionManagerImpl subject;

  @Test
  void registersSubscription() {
    subject.subscribe(principal, "#hashtag");
    verify(cache).register(principal, "#hashtag");
  }
}
```

**Don't** use `@SpringBootTest` for unit tests — it starts the full application context (slow, unnecessary, couples to config). Use slice annotations when you need some Spring machinery.

## Slice tests — minimal Spring context

### `@WebMvcTest` for controllers

```java
@WebMvcTest(SubscriptionController.class)
class SubscriptionControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean SubscriptionManager manager;    // Spring 6.2+: @MockitoBean replaces @MockBean

  @Test
  void returns400OnInvalidHashtag() throws Exception {
    mvc.perform(post("/api/subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"hashtag":"bad!!char"}"""))
       .andExpect(status().isBadRequest());
  }
}
```

Loads only MVC infrastructure + the specified controller + its advice. Filters are usually excluded — if you need `FallbackSecurityHeadersFilter` in the test, register via `@Import(FallbackSecurityHeadersFilter.class)`.

### `@MockitoBean` vs `@MockBean`

In Spring Boot 3.4 / Spring Framework 6.2, `@MockBean` is **deprecated** in favor of `@MockitoBean` (plus `@MockitoSpyBean`). Use the new annotations in new tests; migrate existing usages opportunistically.

## Integration tests (`*IT.java`) — full context

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigurationIT {
  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;

  @Test
  void preflightAllowsExpectedOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin("https://glacier.example.com");
    headers.setAccessControlRequestMethod(HttpMethod.POST);

    ResponseEntity<Void> response = rest.exchange(
        "/api/subscriptions", HttpMethod.OPTIONS,
        new HttpEntity<>(headers), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("https://glacier.example.com");
  }
}
```

`@SpringBootTest` starts the full context — expensive; use for: security/CORS/actuator verification, WebSocket-STOMP integration, cross-component assertions.

## Mocking bigbone — MockWebServer at the HTTP level, NOT Mockito at the client API

Glacier's explicit rule: *e2e runs against the real dockerized Mastodon stack, never a mock*. For **integration tests**, the parallel principle: mock at the **HTTP wire**, not at bigbone's Kotlin interface.

```java
class MastodonConfigurationIT {
  static MockWebServer mockMastodon;

  @BeforeAll
  static void start() throws IOException {
    mockMastodon = new MockWebServer();
    mockMastodon.start();
  }

  @AfterAll
  static void stop() throws IOException { mockMastodon.shutdown(); }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("mastodon.instance", () -> mockMastodon.getHostName());
    r.add("mastodon.port",     () -> mockMastodon.getPort());
    r.add("mastodon.https",    () -> "false");
  }

  @Test
  void honorsRetryAfterOn429() {
    mockMastodon.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "2"));
    // ... assert client back-off behaviour
  }
}
```

**Why MockWebServer beats `Mockito.mock(MastodonClient.class)`**:
- Tests the real HTTP wire (status codes, headers, bodies, timeouts).
- Exercises your retry/backoff/circuit-breaker interceptors.
- Catches API-version mismatches when bigbone updates.
- Validates your own `OkHttpClient` configuration code.

Mocking the client interface only proves you correctly call the method you already told the mock to answer to — tautological.

## Testing WebSocket / STOMP

Handler logic: unit-test `@MessageMapping` methods directly (they're just methods).

Integration: `WebSocketStompClient`:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSubscriptionIT {
  @LocalServerPort int port;

  @Test
  void subscribesAndReceivesMessage() throws Exception {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new MappingJackson2MessageConverter());

    StompSession session = client.connectAsync(
        "ws://localhost:" + port + "/api/ws",
        new StompSessionHandlerAdapter() {}
    ).get(2, TimeUnit.SECONDS);

    BlockingQueue<Object> received = new LinkedBlockingQueue<>();
    session.subscribe("/topic/hashtag/test", new StompFrameHandler() {
      public Type getPayloadType(StompHeaders h) { return TootMessage.class; }
      public void handleFrame(StompHeaders h, Object payload) { received.add(payload); }
    });

    // trigger a publish server-side (via a Spring-injected service in the test)
    Object msg = received.poll(5, TimeUnit.SECONDS);
    assertThat(msg).isNotNull();
  }
}
```

## Jacoco coverage — what passes and what doesn't

`./mvnw verify` fails if new code drops instruction coverage below 45 % or branch below 35 % (bundle-wide).

- Run locally before commit: `./mvnw verify`.
- Report: `target/site/jacoco/index.html`.
- Genuinely untestable code (boilerplate, `main`): exclude in `jacoco-maven-plugin` `<excludes>` with a PR-justified reason.

**Don't** write trivial coverage-padding tests to hit the threshold (`assertEquals(x, bean.getX())`). They add maintenance cost and no protection. Behavioural tests at slightly lower coverage beat noise at higher coverage.

## Parameterized tests for invariants

```java
@ParameterizedTest
@ValueSource(strings = {
    "http://127.0.0.1/", "http://169.254.169.254/",
    "http://localhost/",  "http://10.0.0.1/"
})
void blocksPrivateAndMetadataAddresses(String url) {
  assertThatThrownBy(() -> embedFetcher.fetch(URI.create(url)))
      .isInstanceOf(SsrfBlockedException.class);
}
```

Prefer `@ParameterizedTest` over N near-identical `@Test` for invariant checks (SSRF blocklist, validator boundaries, rate-limit buckets).

## Architecture tests — already established in Glacier

`CodebaseConstraintTest` enforces structural invariants (likely via ArchUnit or similar). When you add architectural rules (e.g., "controllers must not depend on mastodon-client classes directly"), encode them there — it's the most reliable way to prevent architectural drift, more robust than code reviews alone.

## AssertJ over JUnit assertions — stylistic convention

```java
// Preferred
assertThat(result).isEqualTo(expected);
assertThatThrownBy(() -> subject.doThing()).isInstanceOf(MyException.class);

// Avoid in new tests
assertEquals(expected, result);
```

AssertJ has better chainability and failure messages. Existing tests mix both — in new code stick to AssertJ consistently.

## What Claude gets wrong without this skill

- Names an integration test `*Test.java` → wrong runner binding, missed by `./mvnw verify`.
- Uses `@SpringBootTest` for unit tests → slow, masks true unit-level bugs.
- Mocks `MastodonClient` with Mockito → tests the mock, not the HTTP behaviour.
- Uses deprecated `@MockBean` in new code — should be `@MockitoBean`.
- Writes trivial coverage-padding tests.
- Forgets `@DynamicPropertySource` when swapping config at test time → falls back to hardcoded env vars.
- Uses `Thread.sleep` in integration tests → flaky.
- Mixes AssertJ + JUnit-assertions in the same test arbitrarily.

## References
- Unit example: `src/test/java/de/seism0saurus/glacier/mastodon/SubscriptionManagerImplTest.java`
- Integration example: `src/test/java/de/seism0saurus/glacier/mastodon/MastodonConfigurationIT.java`
- Controller slice: `src/test/java/de/seism0saurus/glacier/webservice/FallbackControllerAdviceTest.java`
- CORS IT: `src/test/java/de/seism0saurus/glacier/webservice/CorsConfigurationIT.java`
- Architecture test: `src/test/java/de/seism0saurus/glacier/util/CodebaseConstraintTest.java`
- Jacoco config: `pom.xml` (`jacoco-maven-plugin`)
- Test-pyramid rule (authoritative): project memory `feedback_testing_pyramid.md`
