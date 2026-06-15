package de.seism0saurus.glacier.share.application;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArchUnit structural gates for the ShareViewStompRelay migration (ADR-RELAY-01 to ADR-RELAY-06).
 *
 * <p>These rules enforce the security invariants agreed in Phase 1 planning:
 * <ol>
 *   <li>ARCH-RELAY-01: No {@code @Async} on {@code @EventListener} methods in {@code share.application}.</li>
 *   <li>ARCH-RELAY-02: {@code ShareLinkActivityRegistry.register()} only called from
 *       {@code webservice.messaging.ShareViewPrincipalHandler}.</li>
 *   <li>ARCH-RELAY-02b: {@code ShareLinkActivityRegistry.unregister()} only called from
 *       {@code share.application.ShareViewStompRelay}.</li>
 *   <li>ARCH-RELAY-03: Event record constructors only called from
 *       {@code share.application.ShareLinkServiceImpl}.</li>
 *   <li>ARCH-RELAY-04: No class outside {@code share.application} calls the deprecated
 *       {@code listBySharer} or {@code findAllBySharer}; no {@code @SuppressWarnings("deprecation")}
 *       for these methods in production code.</li>
 *   <li>ARCH-RELAY-05: No {@code @Async} on any class or method in {@code share.application}.</li>
 *   <li>ARCH-RELAY-06: {@code ShareLinkServiceImpl} must NOT depend on {@code ShareViewStompRelay}.</li>
 * </ol>
 *
 * <p>References: SR-RELAY-01, SR-RELAY-02, SR-RELAY-03, SR-RELAY-04, SR-RELAY-23;
 * ADR-RELAY-02, ADR-RELAY-03.
 */
class ShareRelayArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void loadClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier");
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-01: No @Async on @EventListener methods in share.application
    // SR-RELAY-01: event delivery must be synchronous
    // -------------------------------------------------------------------------

    /**
     * SR-RELAY-01 / ARCH-RELAY-01: no method annotated with {@code @EventListener} in the
     * {@code share.application} package may also be annotated with {@code @Async}.
     *
     * <p>Asynchronous event delivery would break the atomicity guarantee of
     * {@code unregister() + pushRevocation()} (SR-RELAY-02) and could cause
     * double-delivery or missed revocations.
     */
    @Test
    void noAsyncOnEventListenerMethods_inShareApplication() {
        // ARCH-RELAY-01: @EventListener methods must not be @Async in share.application
        // SR-RELAY-01: synchronous delivery is a correctness requirement
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat()
                .resideInAPackage("de.seism0saurus.glacier.share.application..")
                .and().areAnnotatedWith("org.springframework.context.event.EventListener")
                .should().beAnnotatedWith("org.springframework.scheduling.annotation.Async")
                .because("SR-RELAY-01 / ARCH-RELAY-01: @EventListener methods in share.application "
                        + "must be synchronous — @Async would break the atomicity of "
                        + "unregister() + pushRevocation() and could cause missed revocations");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-02: register() only callable from ShareViewPrincipalHandler
    // SR-RELAY-03: register() access restricted
    // -------------------------------------------------------------------------

    /**
     * ARCH-RELAY-02 / SR-RELAY-03: {@code ShareLinkActivityRegistry.register()} may only be
     * called from {@code webservice.messaging.ShareViewPrincipalHandler} (handshake path)
     * or {@code share.application.ShareViewStompRelay} ({@code onActivate} event listener path).
     *
     * <p>The handshake path ({@code ShareViewPrincipalHandler}) performs the full TOCTOU-safe
     * re-resolve under the per-linkId lock (SR-RELAY-06, SR-RELAY-07).
     * The {@code onActivate} path populates the routing table when a link is created — the link
     * was just saved by {@code ShareLinkServiceImpl} in the same call chain, so no
     * concurrent revocation race exists at that point.
     *
     * <p>Restricting call sites to these two callers prevents arbitrary code from
     * injecting stale or unvalidated link IDs into the routing table.
     */
    @Test
    void registryRegister_onlyCalledFromShareViewPrincipalHandlerOrShareViewStompRelay() {
        // ARCH-RELAY-02: register() call-site restriction
        // SR-RELAY-03: prevents unauthorised entries in the routing table
        DescribedPredicate<JavaMethodCall> callsRegister =
                new DescribedPredicate<>("call to ShareLinkActivityRegistry.register(...)") {
                    @Override
                    public boolean test(final JavaMethodCall call) {
                        return call.getTarget().getName().equals("register")
                                && call.getTarget().getOwner().getName().contains("ShareLinkActivityRegistry");
                    }
                };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("de.seism0saurus.glacier.webservice.messaging..")
                .and().doNotHaveSimpleName("ShareViewPrincipalHandler")
                .and().doNotHaveSimpleName("ShareViewStompRelay")
                .should().callMethodWhere(callsRegister)
                .because("ARCH-RELAY-02: ShareLinkActivityRegistry.register() must only be called "
                        + "from webservice.messaging.ShareViewPrincipalHandler (handshake path, TOCTOU-safe) "
                        + "or share.application.ShareViewStompRelay (onActivate event path) "
                        + "(SR-RELAY-03: prevents unauthorised routing-table entries)");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-02b: unregister() only callable from ShareViewStompRelay
    // SR-RELAY-03: unregister() access restricted
    // -------------------------------------------------------------------------

    /**
     * ARCH-RELAY-02b / SR-RELAY-03: {@code ShareLinkActivityRegistry.unregister()} may only be
     * called from {@code share.application.ShareViewStompRelay}.
     *
     * <p>Restricting call sites ensures that unregistration only happens as part of the
     * atomic revocation flow (unregister → log → pushRevocation).
     */
    @Test
    void registryUnregister_onlyCalledFromShareViewStompRelay() {
        // ARCH-RELAY-02b: unregister() call-site restriction
        // SR-RELAY-03: prevents partial revocation (unregister without pushRevocation)
        DescribedPredicate<JavaMethodCall> callsUnregister =
                new DescribedPredicate<>("call to ShareLinkActivityRegistry.unregister(...)") {
                    @Override
                    public boolean test(final JavaMethodCall call) {
                        return call.getTarget().getName().equals("unregister")
                                && call.getTarget().getOwner().getName().contains("ShareLinkActivityRegistry");
                    }
                };

        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("ShareViewStompRelay")
                .should().callMethodWhere(callsUnregister)
                .because("ARCH-RELAY-02b: ShareLinkActivityRegistry.unregister() must only be called "
                        + "from share.application.ShareViewStompRelay "
                        + "(SR-RELAY-03: ensures unregister is always paired with pushRevocation)");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-03: Event record constructors only called from ShareLinkServiceImpl
    // SR-RELAY-04: event constructors package-private; only ShareLinkServiceImpl emits
    // -------------------------------------------------------------------------

    /**
     * ARCH-RELAY-03 / SR-RELAY-04: the constructors of {@link ShareLinkActivatedEvent}
     * and {@link ShareLinkRevokedEvent} may only be called from
     * {@code share.application.ShareLinkServiceImpl}.
     *
     * <p>Package-private constructors enforce this at the Java language level; this
     * ArchUnit rule provides an explicit, documented cross-check and a human-readable
     * error message.
     */
    @Test
    void eventRecordConstructors_onlyCalledFromShareLinkServiceImpl() {
        // ARCH-RELAY-03: event record constructors restricted to ShareLinkServiceImpl
        // SR-RELAY-04: prevents rogue emitters from forging lifecycle events
        DescribedPredicate<JavaConstructorCall> callsEventConstructor =
                new DescribedPredicate<>("call to ShareLinkActivatedEvent or ShareLinkRevokedEvent constructor") {
                    @Override
                    public boolean test(final JavaConstructorCall call) {
                        String ownerName = call.getTarget().getOwner().getName();
                        return ownerName.contains("ShareLinkActivatedEvent")
                                || ownerName.contains("ShareLinkRevokedEvent");
                    }
                };

        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("ShareLinkServiceImpl")
                .should().callConstructorWhere(callsEventConstructor)
                .because("ARCH-RELAY-03: ShareLinkActivatedEvent and ShareLinkRevokedEvent constructors "
                        + "are package-private; only ShareLinkServiceImpl (same package) may construct "
                        + "them — prevents rogue emitters from forging lifecycle events (SR-RELAY-04)");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-04: No class outside share.application calls listBySharer or findAllBySharer
    // -------------------------------------------------------------------------

    /**
     * ARCH-RELAY-04: no class outside {@code share.application} may call
     * {@code ShareLinkService.listBySharer} or {@code ShareLinkRepository.findAllBySharer}.
     *
     * <p>After the migration, these deprecated methods are restricted to the
     * {@code share.application} package (where the deprecated {@code listBySharer}
     * implementation in {@link ShareLinkServiceImpl} is the sole internal caller, and
     * {@code ShareViewStompRelay} no longer calls them at all).
     */
    @Test
    void listBySharerAndFindAllBySharer_notCalledOutsideShareApplication() {
        // ARCH-RELAY-04: deprecated retrieval methods must not be used outside share.application
        DescribedPredicate<JavaMethodCall> callsDeprecatedListMethods =
                new DescribedPredicate<>("call to listBySharer or findAllBySharer") {
                    @Override
                    public boolean test(final JavaMethodCall call) {
                        String methodName = call.getTarget().getName();
                        return methodName.equals("listBySharer") || methodName.equals("findAllBySharer");
                    }
                };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("de.seism0saurus.glacier.share.application..")
                .should().callMethodWhere(callsDeprecatedListMethods)
                .because("ARCH-RELAY-04: ShareLinkService.listBySharer and "
                        + "ShareLinkRepository.findAllBySharer are deprecated and incompatible "
                        + "with the SQLite adapter (returns empty). "
                        + "Only share.application may call them (backward-compat shim only).");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-05: No @Async on any class or method in share.application
    // SR-RELAY-01: synchronous event delivery is required
    // -------------------------------------------------------------------------

    /**
     * ARCH-RELAY-05 / SR-RELAY-01: no class or method in {@code share.application} may
     * be annotated with {@code @Async}.
     *
     * <p>Asynchronous processing in the relay layer would break the invariant that
     * toot routing and revocation are synchronous with the event emission from
     * {@link ShareLinkServiceImpl}.
     */
    @Test
    void noAsyncInShareApplicationPackage() {
        // ARCH-RELAY-05: @Async forbidden in share.application entirely
        // SR-RELAY-01: all event processing must be synchronous
        DescribedPredicate<JavaAnnotation<?>> isAsync =
                new DescribedPredicate<>("is @Async") {
                    @Override
                    public boolean test(final JavaAnnotation<?> annotation) {
                        return annotation.getRawType().getName()
                                .equals("org.springframework.scheduling.annotation.Async");
                    }
                };

        ArchRule noAsyncOnClasses = noClasses()
                .that().resideInAPackage("de.seism0saurus.glacier.share.application..")
                .should().beAnnotatedWith("org.springframework.scheduling.annotation.Async")
                .because("ARCH-RELAY-05 / SR-RELAY-01: @Async is forbidden in share.application — "
                        + "asynchronous processing would break event-delivery ordering guarantees");

        ArchRule noAsyncOnMethods = noMethods()
                .that().areDeclaredInClassesThat()
                .resideInAPackage("de.seism0saurus.glacier.share.application..")
                .should().beAnnotatedWith("org.springframework.scheduling.annotation.Async")
                .because("ARCH-RELAY-05 / SR-RELAY-01: @Async is forbidden on methods in "
                        + "share.application — asynchronous processing would break "
                        + "event-delivery ordering guarantees");

        noAsyncOnClasses.check(productionClasses);
        noAsyncOnMethods.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // ARCH-RELAY-06: ShareLinkServiceImpl must NOT depend on ShareViewStompRelay
    // SR-RELAY-23: prevents circular dependency and double pushRevocation()
    // -------------------------------------------------------------------------

    /**
     * ARCH-RELAY-06 / SR-RELAY-23: {@code ShareLinkServiceImpl} must not import or reference
     * {@code ShareViewStompRelay} — no field, constructor parameter, or import.
     *
     * <p>Retaining the direct {@code shareViewStompRelay.pushRevocation()} call alongside
     * the new {@code @EventListener onRevoke} path would cause double STOMP control frame
     * delivery and duplicate AUDIT log entries per revoke.
     */
    @Test
    void shareLinkServiceImpl_mustNotDependOn_shareViewStompRelay() {
        // ARCH-RELAY-06: circular dependency between ShareLinkServiceImpl and ShareViewStompRelay
        // SR-RELAY-23: retaining direct call would cause double pushRevocation()
        ArchRule rule = noClasses()
                .that().haveSimpleName("ShareLinkServiceImpl")
                .should().dependOnClassesThat().haveSimpleName("ShareViewStompRelay")
                .because("ARCH-RELAY-06 / SR-RELAY-23: ShareLinkServiceImpl must not depend on "
                        + "ShareViewStompRelay — the direct pushRevocation() call has been removed "
                        + "to prevent double STOMP control frame delivery and duplicate AUDIT entries. "
                        + "Revocation is now handled by the @EventListener path in ShareViewStompRelay.");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // Self-pinning sentinels
    // -------------------------------------------------------------------------

    /**
     * Self-pinning sentinel for ARCH-RELAY-06: the rule description must reference the
     * governing security requirement ID to preserve traceability.
     */
    @Test
    void archRelay06Rule_becauseClause_containsRequirementReference() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("ShareLinkServiceImpl")
                .should().dependOnClassesThat().haveSimpleName("ShareViewStompRelay")
                .because("ARCH-RELAY-06 / SR-RELAY-23: ShareLinkServiceImpl must not depend on "
                        + "ShareViewStompRelay — the direct pushRevocation() call has been removed "
                        + "to prevent double STOMP control frame delivery and duplicate AUDIT entries. "
                        + "Revocation is now handled by the @EventListener path in ShareViewStompRelay.");

        List<String> ruleTexts = List.of(rule.getDescription());
        assertThat(ruleTexts)
                .as("The ARCH-RELAY-06 rule description must contain 'ARCH-RELAY-06' "
                        + "to preserve traceability to the governing security requirement")
                .allMatch(desc -> desc.contains("ARCH-RELAY-06"));
    }

    /**
     * Self-pinning sentinel for ARCH-RELAY-03: the rule description must reference the
     * governing security requirement ID to preserve traceability.
     */
    @Test
    void archRelay03Rule_becauseClause_containsRequirementReference() {
        DescribedPredicate<JavaConstructorCall> callsEventConstructor =
                new DescribedPredicate<>("call to ShareLinkActivatedEvent or ShareLinkRevokedEvent constructor") {
                    @Override
                    public boolean test(final JavaConstructorCall call) {
                        String ownerName = call.getTarget().getOwner().getName();
                        return ownerName.contains("ShareLinkActivatedEvent")
                                || ownerName.contains("ShareLinkRevokedEvent");
                    }
                };

        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("ShareLinkServiceImpl")
                .should().callConstructorWhere(callsEventConstructor)
                .because("ARCH-RELAY-03: ShareLinkActivatedEvent and ShareLinkRevokedEvent constructors "
                        + "are package-private; only ShareLinkServiceImpl (same package) may construct "
                        + "them — prevents rogue emitters from forging lifecycle events (SR-RELAY-04)");

        List<String> ruleTexts = List.of(rule.getDescription());
        assertThat(ruleTexts)
                .as("The ARCH-RELAY-03 rule description must contain 'ARCH-RELAY-03' "
                        + "to preserve traceability to the governing requirement")
                .allMatch(desc -> desc.contains("ARCH-RELAY-03"));
    }
}
