package de.seism0saurus.glacier.share.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code @ConfigurationProperties} bean for share-link capacity caps (DoS hardening).
 *
 * <p>Exposes the following {@code glacier.share.*} properties:
 * <ul>
 *   <li>{@code glacier.share.maxActivePerSharer} — max concurrent ACTIVE links per wallId
 *       (default: 3).</li>
 *   <li>{@code glacier.share.maxActivePerIp} — max concurrent ACTIVE links created from
 *       a single IP (default: 10).</li>
 *   <li>{@code glacier.share.maxViewersPerLink} — max concurrent viewer sessions per link
 *       (default: 100; enforced by {@code secure-tdd-implementer} lane).</li>
 *   <li>{@code glacier.share.globalMax} — cluster-wide cap on ACTIVE links
 *       (default: 10 000).</li>
 * </ul>
 *
 * <p>All values have setters to allow test-harness overrides without reflection.
 */
@Component
@ConfigurationProperties(prefix = "glacier.share")
public class ShareLinkCapPolicy {

    /** Max concurrent ACTIVE links per sharer wallId. */
    private int maxActivePerSharer = 3;

    /** Max concurrent ACTIVE links created from a single source IP. */
    private int maxActivePerIp = 10;

    /** Max concurrent viewer sessions per share link (enforced in the relay layer). */
    private int maxViewersPerLink = 100;

    /** Cluster-wide maximum number of ACTIVE share links. */
    private int globalMax = 10_000;

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public int getMaxActivePerSharer() { return maxActivePerSharer; }

    public int getMaxActivePerIp() { return maxActivePerIp; }

    public int getMaxViewersPerLink() { return maxViewersPerLink; }

    public int getGlobalMax() { return globalMax; }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    public void setMaxActivePerSharer(final int maxActivePerSharer) {
        this.maxActivePerSharer = maxActivePerSharer;
    }

    public void setMaxActivePerIp(final int maxActivePerIp) {
        this.maxActivePerIp = maxActivePerIp;
    }

    public void setMaxViewersPerLink(final int maxViewersPerLink) {
        this.maxViewersPerLink = maxViewersPerLink;
    }

    public void setGlobalMax(final int globalMax) {
        this.globalMax = globalMax;
    }
}
