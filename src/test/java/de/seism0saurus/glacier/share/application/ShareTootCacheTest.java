package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;
import social.bigbone.api.entity.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareTootCache} — the per-(sharerWallId, hashtag) Status ring backing the
 * share view's initial render + fallback polling (FLAW-3).
 */
class ShareTootCacheTest {

    private static final String WALL = "sharer-wall-id-000000000000000000000000";
    private static final String TAG = "glacier";

    private static Status status(final String id) {
        Status s = mock(Status.class);
        when(s.getId()).thenReturn(id);
        return s;
    }

    @Test
    void recordedToot_isReturnedByRecent() {
        ShareTootCache cache = new ShareTootCache(20);
        Status a = status("a");
        cache.record(WALL, TAG, a);

        assertThat(cache.recent(WALL, TAG)).containsExactly(a);
    }

    @Test
    void recent_isOldestFirst() {
        ShareTootCache cache = new ShareTootCache(20);
        Status a = status("a");
        Status b = status("b");
        cache.record(WALL, TAG, a);
        cache.record(WALL, TAG, b);

        assertThat(cache.recent(WALL, TAG)).containsExactly(a, b);
    }

    @Test
    void record_sameId_replacesAndMovesToMostRecent() {
        ShareTootCache cache = new ShareTootCache(20);
        Status a1 = status("a");
        Status b = status("b");
        Status a2 = status("a"); // edit of "a"
        cache.record(WALL, TAG, a1);
        cache.record(WALL, TAG, b);
        cache.record(WALL, TAG, a2);

        // a1 replaced by a2, moved to the end → [b, a2]
        assertThat(cache.recent(WALL, TAG)).containsExactly(b, a2);
    }

    @Test
    void record_evictsOldestWhenFull() {
        ShareTootCache cache = new ShareTootCache(2);
        Status a = status("a");
        Status b = status("b");
        Status c = status("c");
        cache.record(WALL, TAG, a);
        cache.record(WALL, TAG, b);
        cache.record(WALL, TAG, c); // evicts "a"

        assertThat(cache.recent(WALL, TAG)).containsExactly(b, c);
    }

    @Test
    void remove_dropsTootById() {
        ShareTootCache cache = new ShareTootCache(20);
        Status a = status("a");
        Status b = status("b");
        cache.record(WALL, TAG, a);
        cache.record(WALL, TAG, b);

        cache.remove(WALL, TAG, "a");

        assertThat(cache.recent(WALL, TAG)).containsExactly(b);
    }

    @Test
    void recent_unknownKey_returnsEmpty() {
        ShareTootCache cache = new ShareTootCache(20);
        assertThat(cache.recent(WALL, TAG)).isEmpty();
    }

    @Test
    void keysAreIsolatedPerWallAndHashtag() {
        ShareTootCache cache = new ShareTootCache(20);
        Status a = status("a");
        Status b = status("b");
        cache.record(WALL, "java", a);
        cache.record(WALL, "kotlin", b);

        assertThat(cache.recent(WALL, "java")).containsExactly(a);
        assertThat(cache.recent(WALL, "kotlin")).containsExactly(b);
        assertThat(cache.recent("other-wall", "java")).isEmpty();
    }

    @Test
    void record_ignoresNullBlankOrIdlessInput() {
        ShareTootCache cache = new ShareTootCache(20);
        cache.record(null, TAG, status("a"));
        cache.record("  ", TAG, status("a"));
        cache.record(WALL, null, status("a"));
        cache.record(WALL, TAG, null);
        cache.record(WALL, TAG, status(null)); // no id

        assertThat(cache.recent(WALL, TAG)).isEmpty();
    }
}
