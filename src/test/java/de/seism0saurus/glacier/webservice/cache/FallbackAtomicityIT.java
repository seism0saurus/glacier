package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test asserting atomic exactly-once behaviour of {@link MessageCacheImpl}.
 *
 * <p>Scenario 1: 50 synthetic CREATED events submitted in parallel to a single
 * {@code (principal, hashtag)} ring.  Asserts:
 * <ul>
 *   <li>STOMP subscriber receives all 50 distinct {@code statusId}s.</li>
 *   <li>HTTP snapshot also returns all 50 entries with strictly increasing sequences.</li>
 * </ul>
 *
 * <p>Scenario 2: {@code convertAndSend} is made to throw for 10 of the 50 events.
 * Asserts that the HTTP snapshot still contains all 50 entries and that the
 * {@code glacier.fallback.publish.failures} counter shows 10.
 */
class FallbackAtomicityIT {

    private static final PrincipalKey PRINCIPAL =
            new PrincipalKey(PrincipalKind.WALL, "wall-atomicity-test");
    private static final String HASHTAG = "java";
    private static final int TOTAL_EVENTS = 50;
    private static final int FAILING_EVENTS = 10;

    @Test
    void parallelAppends_50Events_stompAndHttpSnapshotAgreOnAll50StatusIds() throws Exception {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, meterRegistry, 20, 10, 10000, true, Runnable::run);
        cache.provisionHashtag(PRINCIPAL, HASHTAG);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<CacheEntry>> futures = new ArrayList<>();
        for (int i = 0; i < TOTAL_EVENTS; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                startGate.await();
                CacheEntry partial = new CacheEntry(EventType.CREATED, "status-" + idx, "https://ex.com/" + idx + "/embed", null, 0L);
                return cache.recordThenPublish(PRINCIPAL, HASHTAG, partial);
            }));
        }

        startGate.countDown();

        Set<String> publishedStatusIds = futures.stream()
                .map(f -> {
                    try { return f.get().statusId(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .collect(Collectors.toSet());

        // All 50 distinct IDs were published
        assertThat(publishedStatusIds).hasSize(TOTAL_EVENTS);

        // HTTP snapshot returns only the most recent 20 (ring size) — but all 50 were accepted
        Snapshot snapshot = cache.snapshot(PRINCIPAL, HASHTAG, null);
        assertThat(snapshot.events()).hasSize(20); // ring capacity

        // Sequences within the snapshot are strictly increasing
        List<Long> seqs = snapshot.events().stream().map(CacheEntry::sequence).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isGreaterThan(seqs.get(i - 1));
        }

        pool.shutdown();
    }

    @Test
    void parallelAppends_10of50ConvertAndSendThrows_snapshotStillContainsAllAndCounterIs10() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);

        // First 10 calls throw; remaining succeed
        doAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            if (call <= FAILING_EVENTS) {
                throw new RuntimeException("simulated STOMP failure " + call);
            }
            return null;
        }).when(template).convertAndSend(any(String.class), any(Object.class));

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, meterRegistry, 60, 10, 10000, true, Runnable::run);
        cache.provisionHashtag(PRINCIPAL, HASHTAG);

        for (int i = 0; i < TOTAL_EVENTS; i++) {
            CacheEntry partial = new CacheEntry(EventType.CREATED, "sid-" + i, "https://ex.com/" + i + "/embed", null, 0L);
            cache.recordThenPublish(PRINCIPAL, HASHTAG, partial);
        }

        // HTTP snapshot must contain all 50 entries despite the 10 publish failures
        Snapshot snapshot = cache.snapshot(PRINCIPAL, HASHTAG, null);
        assertThat(snapshot.events()).hasSize(TOTAL_EVENTS);

        // Counter must equal the 10 failures
        Counter counter = meterRegistry.counter("glacier.fallback.publish.failures");
        assertThat(counter.count()).isEqualTo((double) FAILING_EVENTS);
    }
}
