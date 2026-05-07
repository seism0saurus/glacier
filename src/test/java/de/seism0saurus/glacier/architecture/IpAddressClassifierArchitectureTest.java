package de.seism0saurus.glacier.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit gate: only {@link de.seism0saurus.glacier.util.IpAddressClassifier} may call
 * the {@link InetAddress} classification methods directly.
 *
 * <p>This gate enforces the Sec-24 invariant introduced alongside Sec-11:
 * the IPv4-mapped IPv6 fix ({@code ::ffff:a.b.c.d}) is only reliable when all
 * code paths go through {@code IpAddressClassifier.isPrivate()} or
 * {@code IpAddressClassifier.isBlockedInetAddress()}, which handle the special case
 * correctly. Direct calls from other classes bypass the fix and reintroduce the
 * silent-failure.
 *
 * <h2>Methods protected by this gate</h2>
 * <ul>
 *   <li>{@link InetAddress#isLoopbackAddress()}</li>
 *   <li>{@link InetAddress#isSiteLocalAddress()}</li>
 *   <li>{@link InetAddress#isLinkLocalAddress()}</li>
 *   <li>{@link InetAddress#isMulticastAddress()}</li>
 *   <li>{@link InetAddress#isAnyLocalAddress()}</li>
 * </ul>
 *
 * <p>{@link InetAddress#getByName(String)}, {@link InetAddress#getAllByName(String)}, and
 * {@link InetAddress#getHostAddress()} are NOT gated — they are pure resolution/formatting
 * methods that do not perform IP classification and can be called freely.
 *
 * <p>Note: {@code IpAddressClassifier} itself is explicitly excluded from the rule
 * (it is the permitted user, not a violator).
 *
 * <p>References: Sec-24; Sec-11/P1-10; OWASP SSRF Prevention Cheat Sheet;
 * spring-input-validation-ssrf skill.
 */
class IpAddressClassifierArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void loadClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier");
    }

    /**
     * Sec-24: no production class other than {@code IpAddressClassifier} may call
     * {@code InetAddress.isLoopbackAddress()}.
     *
     * <p>Prevent future drift: if a developer adds a new SSRF guard or log anonymiser
     * that calls {@code isLoopbackAddress()} directly, this gate will catch it before
     * the IPv4-mapped bypass is reintroduced.
     */
    @Test
    void noDirectCallToIsLoopbackAddress_outsideIpAddressClassifier() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("IpAddressClassifier")
                .should().callMethod(InetAddress.class, "isLoopbackAddress")
                .because("All loopback checks must go through IpAddressClassifier.isPrivate() "
                        + "or IpAddressClassifier.isBlockedInetAddress() to handle IPv4-mapped "
                        + "IPv6 addresses correctly (Sec-11/Sec-24)");
        rule.check(productionClasses);
    }

    /**
     * Sec-24: no production class other than {@code IpAddressClassifier} may call
     * {@code InetAddress.isSiteLocalAddress()}.
     */
    @Test
    void noDirectCallToIsSiteLocalAddress_outsideIpAddressClassifier() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("IpAddressClassifier")
                .should().callMethod(InetAddress.class, "isSiteLocalAddress")
                .because("All site-local checks must go through IpAddressClassifier to handle "
                        + "IPv4-mapped IPv6 addresses correctly (Sec-11/Sec-24)");
        rule.check(productionClasses);
    }

    /**
     * Sec-24: no production class other than {@code IpAddressClassifier} may call
     * {@code InetAddress.isLinkLocalAddress()}.
     */
    @Test
    void noDirectCallToIsLinkLocalAddress_outsideIpAddressClassifier() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("IpAddressClassifier")
                .should().callMethod(InetAddress.class, "isLinkLocalAddress")
                .because("All link-local checks must go through IpAddressClassifier to handle "
                        + "IPv4-mapped IPv6 addresses correctly (Sec-11/Sec-24)");
        rule.check(productionClasses);
    }

    /**
     * Sec-24: no production class other than {@code IpAddressClassifier} may call
     * {@code InetAddress.isMulticastAddress()}.
     */
    @Test
    void noDirectCallToIsMulticastAddress_outsideIpAddressClassifier() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("IpAddressClassifier")
                .should().callMethod(InetAddress.class, "isMulticastAddress")
                .because("All multicast checks must go through IpAddressClassifier (Sec-11/Sec-24)");
        rule.check(productionClasses);
    }

    /**
     * Sec-24: no production class other than {@code IpAddressClassifier} may call
     * {@code InetAddress.isAnyLocalAddress()}.
     */
    @Test
    void noDirectCallToIsAnyLocalAddress_outsideIpAddressClassifier() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("IpAddressClassifier")
                .should().callMethod(InetAddress.class, "isAnyLocalAddress")
                .because("All any-local checks must go through IpAddressClassifier (Sec-11/Sec-24)");
        rule.check(productionClasses);
    }
}
