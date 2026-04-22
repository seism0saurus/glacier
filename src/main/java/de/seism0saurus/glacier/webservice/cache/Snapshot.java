package de.seism0saurus.glacier.webservice.cache;

import java.util.List;

/**
 * A point-in-time snapshot of events from a {@link PerTagRing}.
 *
 * <p>Returned by {@link MessageCache#snapshot} so that callers outside
 * the {@code cache} package can work with the result without needing a
 * reference to the package-private {@link PerTagRing}.
 *
 * @param events    ordered list of {@link CacheEntry} objects whose sequence
 *                  number is greater than the requested {@code since} value;
 *                  defensive copy, safe to hand to callers
 * @param nextSince the highest sequence number in this ring at snapshot time;
 *                  the client should pass this as {@code since} in its next poll
 * @param gap       {@code true} when the requested {@code since} value predates
 *                  the oldest entry still in the ring, meaning some events were
 *                  silently dropped
 */
public record Snapshot(List<CacheEntry> events, long nextSince, boolean gap) {
}
