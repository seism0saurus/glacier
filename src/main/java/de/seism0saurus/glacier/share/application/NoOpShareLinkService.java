package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stub implementation of {@link ShareLinkService}.
 *
 * <p>This stub exists in this lane so the application context starts while the real
 * implementation (owned by {@code tdd-ddd-implementer} in a peer worktree) is not
 * yet merged. It will be replaced by the peer-lane implementation.
 *
 * <p>The {@link ConditionalOnMissingBean} annotation ensures the stub is superseded
 * by any production implementation provided at runtime.
 *
 * @see ShareLinkService
 */
@Service
@ConditionalOnMissingBean(name = "shareLinkServiceImpl")
public class NoOpShareLinkService implements ShareLinkService {

    private static final Logger log = LoggerFactory.getLogger(NoOpShareLinkService.class);

    @Override
    public ShareLink create(String sharerWallId, Instant now) {
        log.warn("NoOpShareLinkService.create called — peer-lane implementation not yet available");
        throw new UnsupportedOperationException("ShareLinkService not implemented yet — waiting for tdd-ddd-implementer");
    }

    @Override
    public Optional<ShareLink> resolve(ShareLinkId id, Instant now) {
        log.debug("NoOpShareLinkService.resolve called — returning empty");
        return Optional.empty();
    }

    @Override
    public void revoke(ShareLinkId id, String callerWallId, Instant now) {
        log.warn("NoOpShareLinkService.revoke called — peer-lane implementation not yet available");
    }

    @Override
    public List<ShareLink> listBySharer(String sharerWallId, Instant now) {
        log.debug("NoOpShareLinkService.listBySharer called — returning empty");
        return List.of();
    }
}
