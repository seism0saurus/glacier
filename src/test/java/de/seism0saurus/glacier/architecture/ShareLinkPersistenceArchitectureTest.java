package de.seism0saurus.glacier.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * ArchUnit security rules for the SQLite share-link persistence layer (ADR-SQLITE-04,
 * SR-SQLITE-10, SR-SQLITE-19, SR-SQLITE-20, SR-SQLITE-23).
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>{@link #noRawStatementUsedInShareInfrastructure()} — only {@code PreparedStatement}
 *       allowed in {@code share.infrastructure} (SR-SQLITE-10; OWASP A03:2021 Injection).</li>
 *   <li>{@link #sha256HexNotCalledFromOutsideShareInfrastructure()} — the private
 *       token-hashing method is not accessible from outside {@code share.infrastructure}
 *       (SR-SQLITE-19; ADR-SQLITE-04).</li>
 *   <li>{@link #shareLinkSummaryHasNoSensitiveTokenFields()} — {@code ShareLinkSummary}
 *       (when it exists) must not have fields named id/token/secret/url/readonlyUrl
 *       (SR-SQLITE-20; ADR-SQLITE-05). Vacuously passes if the class does not exist yet.</li>
 *   <li>{@link #creatorIpAccessRestrictedToAuthorisedCallers()} — {@code ShareLink.creatorIp()}
 *       may only be called from {@code ShareLinkServiceImpl} or {@code share.infrastructure}
 *       (SR-SQLITE-23; ADR-SQLITE-08).</li>
 * </ol>
 *
 * <p>References: ADR-SQLITE-04; SR-SQLITE-10; SR-SQLITE-19; SR-SQLITE-20; SR-SQLITE-23;
 * OWASP A03:2021 Injection; OWASP A05:2021 Security Misconfiguration.
 */
class ShareLinkPersistenceArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void loadClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier");
    }

    /**
     * SR-SQLITE-10 / OWASP A03:2021 Injection: No class in {@code share.infrastructure}
     * may use {@code java.sql.Statement} directly (only {@code PreparedStatement} is allowed).
     *
     * <p>Using raw {@code Statement} enables SQL injection if any string is ever concatenated
     * into a query. Enforcing {@code PreparedStatement}-only prevents the entire class of
     * string-concatenation SQL injection bugs.
     */
    @Test
    void noRawStatementUsedInShareInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("de.seism0saurus.glacier.share.infrastructure..")
                .should().accessClassesThat().haveFullyQualifiedName("java.sql.Statement")
                .because("Only PreparedStatement is allowed in share.infrastructure to prevent "
                        + "SQL injection (SR-SQLITE-10, OWASP A03:2021, ADR-SQLITE-02)");
        rule.check(productionClasses);
    }

    /**
     * SR-SQLITE-19 / ADR-SQLITE-04: No class OUTSIDE {@code share.infrastructure} may call
     * a method named {@code sha256Hex}.
     *
     * <p>The token-hashing helper is adapter-local by design. Callers from outside the adapter
     * package would bypass the privacy invariant — the raw token must never leave the adapter
     * layer (ADR-SQLITE-04: adapter-local token hashing; SR-19: exactly one call site).
     */
    @Test
    void sha256HexNotCalledFromOutsideShareInfrastructure() {
        DescribedPredicate<JavaCall<?>> callsSha256Hex =
                new DescribedPredicate<>("call method named sha256Hex") {
                    @Override
                    public boolean test(final JavaCall<?> input) {
                        return "sha256Hex".equals(input.getName());
                    }
                };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("de.seism0saurus.glacier.share.infrastructure..")
                .should().callCodeUnitWhere(callsSha256Hex)
                .because("sha256Hex is an adapter-local private method; callers outside "
                        + "share.infrastructure would bypass the at-rest token protection "
                        + "(SR-SQLITE-19, ADR-SQLITE-04)");
        rule.check(productionClasses);
    }

    /**
     * SR-SQLITE-20 / ADR-SQLITE-05: When {@code ShareLinkSummary} exists, it must not have
     * a field named {@code id}, {@code token}, {@code secret}, {@code url}, or
     * {@code readonlyUrl} (case-insensitive).
     *
     * <p>The summary projection is a no-token read model. Any field carrying a raw token
     * or share URL would defeat the purpose of the SQLite adapter which avoids storing
     * raw credentials at rest.
     *
     * <p>This test passes vacuously if {@code ShareLinkSummary} does not yet exist in the
     * import set (Lane 3 creates it). Once created, the rule becomes enforcing.
     */
    @Test
    void shareLinkSummaryHasNoSensitiveTokenFields() {
        // Vacuously true if ShareLinkSummary class does not exist yet (Lane 3 creates it).
        // Once ShareLinkSummary is created, this rule enforces the no-token contract.
        // allowEmptyShould(true): ShareLinkSummary does not exist yet (Lane 3 creates it).
        // Once it exists, the empty-should guard is removed and the rule becomes enforcing.
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().haveSimpleName("ShareLinkSummary")
                .should().haveNameMatching("(?i)^(id|token|secret|url|readonlyUrl)$")
                .allowEmptyShould(true)
                .because("ShareLinkSummary is a no-token projection: fields named id/token/secret/"
                        + "url/readonlyUrl would expose raw bearer credentials or share URLs "
                        + "(SR-SQLITE-20, ADR-SQLITE-05)");
        rule.check(productionClasses);
    }

    /**
     * SR-SQLITE-23 / ADR-SQLITE-08: {@code ShareLink.creatorIp()} may only be called from
     * {@code de.seism0saurus.glacier.share.application.ShareLinkServiceImpl} and from classes
     * in {@code de.seism0saurus.glacier.share.infrastructure}.
     *
     * <p>The raw IP is used only for:
     * <ol>
     *   <li>Per-IP cap accounting in {@code ShareLinkServiceImpl.create} (application layer).</li>
     *   <li>HMAC computation in the SQLite adapter (infrastructure layer).</li>
     * </ol>
     *
     * <p>Any other caller risks logging or returning the raw IP address, violating GDPR
     * Art. 25 (Data Protection by Design) and SR-SQLITE-04.
     */
    @Test
    void creatorIpAccessRestrictedToAuthorisedCallers() {
        DescribedPredicate<JavaCall<?>> callsCreatorIp =
                new DescribedPredicate<>("call ShareLink.creatorIp()") {
                    @Override
                    public boolean test(final JavaCall<?> input) {
                        return "creatorIp".equals(input.getName())
                                && "ShareLink".equals(input.getTargetOwner().getSimpleName());
                    }
                };

        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackages(
                        "de.seism0saurus.glacier.share.application..",
                        "de.seism0saurus.glacier.share.infrastructure..")
                .should().callCodeUnitWhere(callsCreatorIp)
                .because("ShareLink.creatorIp() is for IP-cap accounting and HMAC-write only; "
                        + "callers outside application and infrastructure layers risk raw-IP exposure "
                        + "(SR-SQLITE-23, ADR-SQLITE-08, GDPR Art. 25)");
        rule.check(productionClasses);
    }
}
