package de.seism0saurus.glacier;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Startup sanity checks for Glacier's critical configuration invariants.
 *
 * <p><strong>Mixed-state constructor (B6, ADR-P3B-1)</strong>: {@code glacier.cookie.secure}
 * is injected via {@link GlacierCookieProperties} (ADR-P3B-1). {@code glacier.devmode} and
 * {@code mastodon.https} remain on {@code @Value} injection (SR-9/D-14 fence prevents them
 * from being moved to a shared bean in webservice/ packages).
 *
 * <p>Two guards are enforced:
 *
 * <h3>Sec-01 — devmode startup guard</h3>
 * <p>If {@code glacier.devmode=true} is active outside of the {@code dev} or {@code test}
 * Spring profiles, this guard throws {@link IllegalStateException} to prevent silent
 * production misconfiguration. A deployment with {@code devmode=true} in production
 * enables federation MITM on the Bigbone streaming socket ({@code withTrustAllCerts()});
 * all wall content becomes attacker-controlled.
 *
 * <p>References: security_final Sec-01/P1-08; OWASP A05:2021 Security Misconfiguration;
 * NIST SP 800-53 CM-6 Configuration Settings; ASVS V1.14.7 (L2).
 *
 * <h3>Sec-23 — cookie.secure=true ∧ mastodon.https=false contradiction</h3>
 * <p>A deployment with {@code glacier.cookie.secure=true} (SameSite/Secure cookie flags)
 * combined with {@code mastodon.https=false} (HTTP Mastodon endpoint) is a contradictory
 * configuration that may silently break cookie delivery on HTTP backends while the operator
 * believes cookies are protected. This combination is logged at WARN — it is unusual but
 * not necessarily fatal (TLS termination may be handled by a reverse proxy).
 *
 * <p>References: security_final Sec-23; OWASP A05:2021; ASVS V3.4.1 (L1).
 *
 * @see MastodonConfiguration
 */
@Component
public class StartupSanityChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupSanityChecker.class);

    private final boolean devmode;
    private final boolean mastodonHttps;
    private final boolean cookieSecure;
    private final Environment environment;

    /**
     * Constructs the checker with the relevant configuration values.
     *
     * <p>Mixed-state wiring (B6): {@code glacier.cookie.secure} is read from
     * {@link GlacierCookieProperties} (ADR-P3B-1); {@code glacier.devmode} and
     * {@code mastodon.https} remain on {@code @Value} injection.
     *
     * @param devmode       {@code glacier.devmode} — enables unsafe dev-mode TLS overrides
     * @param mastodonHttps {@code mastodon.https} — whether the Mastodon endpoint uses HTTPS
     * @param cookieProps   startup-validated bean for {@code glacier.cookie.*} settings (ADR-P3B-1)
     * @param environment   Spring {@link Environment} for active-profile detection
     */
    public StartupSanityChecker(
            @Value("${glacier.devmode:false}") final boolean devmode,
            @Value("${mastodon.https:true}") final boolean mastodonHttps,
            final GlacierCookieProperties cookieProps,
            final Environment environment) {
        this.devmode = devmode;
        this.mastodonHttps = mastodonHttps;
        this.cookieSecure = Boolean.TRUE.equals(cookieProps.getSecure());
        this.environment = environment;
    }

    /**
     * Validates all startup configuration invariants.
     *
     * <p>Called by Spring after dependency injection. Checks are evaluated in order;
     * the devmode guard is a hard fail ({@link IllegalStateException}),
     * the cookie/https contradiction is a soft WARN.
     *
     * @throws IllegalStateException if {@code glacier.devmode=true} outside the {@code dev} or
     *                               {@code test} Spring profiles (Sec-01)
     */
    @PostConstruct
    public void checkConfigurationInvariants() {
        checkDevmodeNotInProduction();
        checkCookieSecureVsMastodonHttps();
    }

    /**
     * Sec-01: Prevents silent production misconfiguration of {@code glacier.devmode=true}.
     *
     * <p>devmode=true enables {@code withTrustAllCerts()} in {@link MastodonConfiguration},
     * which disables TLS certificate verification on the Bigbone streaming socket and allows
     * MITM attacks on the federation stream. In production (no {@code dev} or {@code test}
     * profile active), this is a critical security misconfiguration.
     *
     * <p>The guard fails fast at startup to prevent the application from serving an
     * attacker-controlled wall. Operators must either:
     * <ul>
     *   <li>Set {@code glacier.devmode=false} (the default), or</li>
     *   <li>Activate the {@code dev} or {@code test} Spring profile explicitly.</li>
     * </ul>
     *
     * @throws IllegalStateException if devmode is active in a production profile
     */
    void checkDevmodeNotInProduction() {
        if (!devmode) {
            return; // no devmode, no problem
        }
        String[] activeProfiles = environment.getActiveProfiles();
        boolean isDevOrTestProfile = Arrays.stream(activeProfiles)
                .anyMatch(p -> "dev".equals(p) || "test".equals(p));

        if (!isDevOrTestProfile) {
            // C5 — secure defaults: devmode outside dev/test profile is never acceptable
            // OWASP A05:2021 Security Misconfiguration — ASVS V1.14.7 (L2)
            throw new IllegalStateException(
                    "glacier.devmode=true is active but neither the 'dev' nor 'test' Spring profile is active. "
                    + "This configuration enables TLS trust-all and is FORBIDDEN in production. "
                    + "Set glacier.devmode=false or activate the 'dev' profile. "
                    + "(Sec-01, OWASP A05:2021, ASVS V1.14.7)");
        }

        LOGGER.warn("glacier.devmode=true is active — this enables withTrustAllCerts() on the Mastodon "
                + "streaming socket. Only acceptable in dev/test profiles. Current profiles: {}",
                Arrays.toString(activeProfiles));
    }

    /**
     * Sec-23: Warns about the contradictory combination of cookie.secure=true and mastodon.https=false.
     *
     * <p>This combination is unusual: the Secure cookie flag requires HTTPS for the browser to
     * send the cookie, but the Mastodon backend connection uses plain HTTP. While TLS termination
     * at a reverse proxy makes this legitimate, it can also indicate a misconfigured deployment
     * where cookies will be silently dropped by browsers.
     *
     * <p>This is a WARN (not a hard fail) because the combination is sometimes intentional
     * (Traefik TLS termination with an HTTP backend). Operators should verify this is deliberate.
     *
     * <p>References: Sec-23; OWASP A05:2021; ASVS V3.4.1 (L1) — cookie Secure flag.
     */
    void checkCookieSecureVsMastodonHttps() {
        if (cookieSecure && !mastodonHttps) {
            // C5 — secure defaults: surface contradictory config at startup
            // OWASP A05:2021 Security Misconfiguration — ASVS V3.4.1 (L1)
            LOGGER.warn("Configuration contradiction detected (Sec-23): "
                    + "glacier.cookie.secure=true requires HTTPS for browsers to send the wallId cookie, "
                    + "but mastodon.https=false sets the Mastodon client to HTTP. "
                    + "If TLS termination is handled by a reverse proxy (Traefik/nginx), this is intentional. "
                    + "Otherwise, verify your deployment: cookies with the Secure flag are silently dropped "
                    + "by browsers over plain HTTP connections. (OWASP A05:2021, ASVS V3.4.1)");
        }
    }
}
