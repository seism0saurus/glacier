package de.seism0saurus.glacier.share.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates cryptographically strong, URL-safe base64url tokens for
 * {@link ShareLinkId} and {@link ShareViewerId}.
 *
 * <p>Token properties:
 * <ul>
 *   <li>Source entropy: 32 bytes from {@link SecureRandom} (256 bits).</li>
 *   <li>Encoding: URL-safe base64url without padding (RFC 4648 §5 alphabet: A-Z a-z 0-9 - _).
 *       32 bytes encode to 43 characters (⌈32×8/6⌉).</li>
 *   <li>Collision probability for 1 000 generated IDs: negligible
 *       ({@code 1000² / 2^{256+1} ≈ 10^{-74}}).</li>
 * </ul>
 *
 * <p>Thread-safety: {@link SecureRandom} is thread-safe by contract; this class holds a
 * single instance.  Virtual-thread-heavy workloads calling this generator concurrently will
 * observe contention on the shared {@link SecureRandom} but not data corruption.
 */
@Component
public class SecureRandomTokenGenerator {

    private static final int ENTROPY_BYTES = 32;
    private final SecureRandom secureRandom;

    /**
     * Default constructor — uses the platform-default {@link SecureRandom} provider.
     * Spring instantiates this as a singleton bean.
     */
    public SecureRandomTokenGenerator() {
        this.secureRandom = new SecureRandom();
    }

    /**
     * Constructor for testing — allows injection of a deterministic {@link SecureRandom}.
     *
     * @param secureRandom the random source; must not be null
     */
    public SecureRandomTokenGenerator(final SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * Generates a new {@link ShareLinkId} with 256-bit entropy.
     *
     * @return a fresh, unique {@link ShareLinkId}; never null
     */
    public ShareLinkId generateShareLinkId() {
        return new ShareLinkId(generateToken());
    }

    /**
     * Generates a new {@link ShareViewerId} with 256-bit entropy.
     *
     * @return a fresh, unique {@link ShareViewerId}; never null
     */
    public ShareViewerId generateShareViewerId() {
        return new ShareViewerId(generateToken());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private String generateToken() {
        byte[] bytes = new byte[ENTROPY_BYTES];
        secureRandom.nextBytes(bytes);
        // URL-safe base64 without padding: produces 43 chars for 32 bytes
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
