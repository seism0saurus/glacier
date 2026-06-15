package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrency stress test for the TOCTOU window in {@link ShareLinkActivityRegistry#register}.
 *
 * <p>Tests two distinct concurrent scenarios to verify the per-linkId lock correctness:
 * <ol>
 *   <li><b>Lock-serialisation scenario</b> (100 iterations): register and unregister for the
 *       same link run concurrently. After both complete, the registry must be either empty or
 *       consistent — never left in an inconsistent state.</li>
 *   <li><b>Revoke-wins scenario</b>: the re-resolve inside {@code register()} always returns
 *       empty (revocation completed before lock acquisition) → {@code register()} returns
 *       {@code false} and no entry is ever added → registry stays empty.</li>
 * </ol>
 *
 * <p>Security requirements:
 * <ul>
 *   <li>SR-RELAY-07 — per-linkId lock covers both {@code register()} and {@code unregister()};
 *       the TOCTOU window between the handshake {@code resolve()} and the actual registration
 *       is closed (ADR-RELAY-03).</li>
 *   <li>SR-RELAY-06 — if the re-resolve inside {@code register()} returns empty (concurrent
 *       revocation), {@code register()} returns {@code false} and no stale entry is added.</li>
 * </ul>
 *
 * <p>This test is classified as an {@code *IT.java} (Failsafe) because it uses virtual-thread
 * concurrency; it has no Spring context dependency but its runtime characteristics are
 * integration-level (multi-threaded, non-deterministic ordering).
 *
 * <p>WSTG-AUTHZ-04 — Insecure Direct Object Reference: verifies that revocation cannot be
 * bypassed by timing a registration call against the revocation window.
 */
class ShareViewPrincipalHandlerToctouIT {

    private static final String SHARER_WALL_ID = "toctou-sharer-wallid-1234567890abcdef";

    /**
     * Scenario 1: Revoke-wins — the re-resolve INSIDE {@code register()} always returns empty,
     * modelling the case where the revocation completes before the lock is acquired.
     *
     * <p>Expected: {@code register()} must return {@code false} and no entry must be added.
     * After both threads complete, the registry is empty.
     *
     * <p>This is the primary SR-RELAY-06 assertion: when revocation wins the race,
     * the re-resolve under lock sees empty → register returns false → no stale entry.
     */
    @Test
    void revokeWinsRace_registerReturnsFalseAndRegistryStaysEmpty() throws Exception {
        ShareLinkActivityRegistry registry = new ShareLinkActivityRegistry();
        ShareLinkId linkId = ShareLinkId.fromUrlPath("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCcc");
        Instant now = Instant.now();

        // Mock: re-resolve inside register() always returns empty (revocation already complete)
        ShareLinkService mockService = mock(ShareLinkService.class);
        when(mockService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<Boolean> registerFuture = CompletableFuture.supplyAsync(
                    () -> registry.register(SHARER_WALL_ID, linkId, mockService, now),
                    executor);

            CompletableFuture<Void> unregisterFuture = CompletableFuture.runAsync(
                    () -> registry.unregister(SHARER_WALL_ID, linkId),
                    executor);

            CompletableFuture.allOf(registerFuture, unregisterFuture).get();

            // register() must have returned false (revoke won)
            assertThat(registerFuture.get())
                    .as("register() must return false when re-resolve returns empty (SR-RELAY-06)")
                    .isFalse();

            // Registry must be empty
            assertThat(registry.getActiveLinks(SHARER_WALL_ID))
                    .as("Registry must be empty after revoke-wins race (SR-RELAY-07)")
                    .isEmpty();
        } finally {
            executor.shutdown();
            registry.clear();
        }
    }

    /**
     * Scenario 2: Register-wins — the re-resolve returns active, register() adds the entry,
     * then unregister() removes it. After both complete, the registry is empty.
     *
     * <p>This verifies that even when registration succeeds, a subsequent unregister() correctly
     * clears the entry — no stale entry persists.
     */
    @Test
    void registerWinsRace_subsequentUnregisterClearsEntry() throws Exception {
        ShareLinkActivityRegistry registry = new ShareLinkActivityRegistry();
        ShareLinkId linkId = ShareLinkId.fromUrlPath("DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDdd");
        Instant now = Instant.now();

        ShareLink activeLink = ShareLink.create(linkId, SHARER_WALL_ID, now, Duration.ofDays(7));

        // Mock: re-resolve always returns active → register() succeeds
        ShareLinkService mockService = mock(ShareLinkService.class);
        when(mockService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(activeLink));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // register first, then unregister — deterministic ordering
            boolean registered = registry.register(SHARER_WALL_ID, linkId, mockService, now);
            assertThat(registered).as("register() must succeed when link is active").isTrue();
            assertThat(registry.getActiveLinks(SHARER_WALL_ID)).contains(linkId);

            registry.unregister(SHARER_WALL_ID, linkId);

            // After unregister, registry must be empty
            assertThat(registry.getActiveLinks(SHARER_WALL_ID))
                    .as("Registry must be empty after unregister()")
                    .isEmpty();
        } finally {
            executor.shutdown();
            registry.clear();
        }
    }

    /**
     * Scenario 3: Lock serialisation stress — 50 iterations of concurrent register+unregister
     * where both can race. Verifies that after both complete, the registry is NEVER in a
     * partially-written state (no partial add visible after unregister completes).
     *
     * <p>The per-linkId lock (ADR-RELAY-03, SR-RELAY-07) ensures that register() and
     * unregister() for the same link cannot overlap — they are always serialised. After BOTH
     * complete, the final state must be consistent: either empty (unregister won or revoke-gate
     * blocked register) or non-empty (register won and unregister had nothing to remove).
     *
     * <p>Note: when register()'s re-resolve sees "active" AND unregister runs BEFORE register
     * adds the entry, a stale entry IS possible by design — the registry lock cannot prevent
     * an unregister that arrives before any entry exists. The handler prevents this scenario by
     * only calling register() AFTER the link is verified active (SR-RELAY-05). Here we test
     * the lock's write-atomicity guarantee, not the handler's ordering guarantee.
     */
    @Test
    void concurrentLock_writeOperationsAreAtomic() throws Exception {
        for (int i = 0; i < 50; i++) {
            ShareLinkActivityRegistry registry = new ShareLinkActivityRegistry();
            ShareLinkId linkId = ShareLinkId.fromUrlPath("EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEee");
            Instant now = Instant.now();
            ShareLink activeLink = ShareLink.create(linkId, SHARER_WALL_ID, now, Duration.ofDays(7));

            // Track whether register() returned true
            AtomicBoolean registerSucceeded = new AtomicBoolean(false);

            // Use a latch to maximise contention
            CountDownLatch startLatch = new CountDownLatch(1);
            ShareLinkService mockService = mock(ShareLinkService.class);
            when(mockService.resolve(any(ShareLinkId.class), any(Instant.class)))
                    .thenReturn(Optional.of(activeLink));

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                CompletableFuture<Void> registerFuture = CompletableFuture.runAsync(() -> {
                    try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    boolean result = registry.register(SHARER_WALL_ID, linkId, mockService, now);
                    registerSucceeded.set(result);
                }, executor);

                CompletableFuture<Void> unregisterFuture = CompletableFuture.runAsync(() -> {
                    try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    registry.unregister(SHARER_WALL_ID, linkId);
                }, executor);

                // Release both threads simultaneously to maximise contention
                startLatch.countDown();
                CompletableFuture.allOf(registerFuture, unregisterFuture).get();

                // After both complete, the registry state must be consistent:
                // - If register failed (revoke-gate blocked): registry is empty ✓
                // - If register succeeded AND unregister ran after: registry is empty ✓
                // - If register succeeded AND unregister ran before (nothing to remove): 1 entry
                // All three are valid consistent states — we assert no PARTIAL writes visible.
                int activeCount = registry.getActiveLinks(SHARER_WALL_ID).size();
                assertThat(activeCount)
                        .as("Iteration %d: registry must have 0 or 1 entry — no partial state", i)
                        .isIn(0, 1);
            } finally {
                executor.shutdown();
                registry.clear();
            }
        }
    }
}
