package de.seism0saurus.glacier.eventtype;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import de.seism0saurus.glacier.mastodon.StompEventType;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ArchUnit exclusivity gate: {@link EventTypeMapping} is the ONLY class in {@code src/main}
 * that declares a method which maps between the {@link StatusMessage} subclass hierarchy
 * and the {@link StompEventType} vocabulary (ADR-P3A-4).
 *
 * <p>The guarded invariant: no production class outside the {@code eventtype} package may
 * declare a method that returns {@link Optional} AND simultaneously accesses
 * {@link StompEventType} enum constants directly. The ONLY valid place for such a method
 * is {@link EventTypeMapping} — the single-authority translator.
 *
 * <p>Rule precision: classes that use {@link StompEventType#suffix()} for topic-path
 * construction (e.g. {@code StompEventType.CREATION.suffix()} in {@code StompCallback})
 * are NOT banned by this rule, because those calls exist in {@code void} methods, not in
 * methods returning {@code Optional}. The rule targets the exact forbidden translator-method
 * shape: a method returning {@code Optional<StompEventType>} outside the {@code eventtype}
 * package.
 *
 * <p>The second test ({@link #exclusivityRule_catchesDeliberateViolation}) proves the gate
 * is live by running the rule against a deliberately violating inner class.
 *
 * <p>ADR references: ADR-P3A-4 (cross-vocabulary translation authority),
 * ADR-F6-INFO-2-A (StompEventType and EventType belong to separate bounded contexts).
 */
class EventTypeMappingExclusivityTest {

    /**
     * A deliberately violating helper class used exclusively by the regression test.
     *
     * <p>This static inner class is declared in TEST sources. It contains a method whose
     * signature mirrors the forbidden {@code eventTypeFor} pattern that was removed from
     * {@code StompCallback}: a method returning {@code Optional} that accesses
     * {@link StompEventType} constants while taking a {@link StatusMessage} subtype parameter.
     *
     * <p>The regression test imports this class via {@link ClassFileImporter} (without the
     * {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS} filter) and verifies the rule fires.
     */
    static class UnauthorisedTranslatorViolation {
        /**
         * The exact forbidden pattern: a method returning {@link Optional} that accesses
         * {@link StompEventType} enum constants outside the {@code eventtype} package.
         * This is the shape of {@code StompCallback.eventTypeFor} before it was removed.
         */
        @SuppressWarnings("unused")
        public static Optional<StompEventType> illegallyMapsClassToStompType(
                Class<? extends StatusMessage> clazz) {
            if (StatusCreatedMessage.class.equals(clazz)) return Optional.of(StompEventType.CREATION);
            if (StatusUpdatedMessage.class.equals(clazz)) return Optional.of(StompEventType.MODIFICATION);
            return Optional.empty();
        }
    }

    private static JavaClasses productionClasses;

    @BeforeAll
    static void loadProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier");
    }

    /**
     * Builds the ArchUnit rule that enforces the cross-vocabulary translation exclusivity
     * invariant (ADR-P3A-4).
     *
     * <p>The rule: no method outside the {@code de.seism0saurus.glacier.eventtype} package
     * may have a raw return type of {@link Optional} AND simultaneously access members of
     * {@link StompEventType}.
     *
     * <p>This targets the exact forbidden translator-method shape: a method such as
     * {@code Optional<StompEventType> eventTypeFor(Class<? extends StatusMessage>)}.
     * Methods in {@code StompCallback} that use {@link StompEventType#suffix()} for topic
     * routing have return type {@code void} and are therefore not caught by this rule.
     *
     * <p>The rule is extracted into a factory method so it can be shared between the
     * enforcement test and the regression test without duplication.
     *
     * @return the ArchUnit rule, ready to be checked against any {@link JavaClasses} set
     */
    private static ArchRule buildExclusivityRule() {
        // Rule: no method that (a) lives outside the eventtype package AND (b) returns Optional
        // may call the StompEventType constructor (via Optional.of(StompEventType.X)).
        // The specific call we guard is Optional.of(Object) invoked in a method that resolves
        // to Optional<StompEventType> — detected by checking the method has raw return type
        // Optional AND calls the StompEventType.valueOf or accesses StompEventType fields.
        //
        // ArchUnit MethodsShould does not expose accessClassesThat() — we express the rule
        // by requiring that methods outside eventtype with return type Optional must NOT
        // access StompEventType fields. We use the fieldAccess form: callMethod is not available
        // on MethodsShould; instead we use noClasses() with combined .that() predicates.
        //
        // Final working expression: noClasses outside eventtype that have ANY method returning
        // Optional should access StompEventType. This is encoded as a class-level rule where
        // the class-predicate restricts to classes that contain such a method.
        DescribedPredicate<JavaMethod> returnsOptional =
                new DescribedPredicate<>("return type is Optional") {
                    @Override
                    public boolean test(JavaMethod method) {
                        return Optional.class.getName().equals(method.getRawReturnType().getName());
                    }
                };
        return noClasses()
                .that().resideOutsideOfPackage("de.seism0saurus.glacier.eventtype..")
                .and().containAnyMethodsThat(returnsOptional)
                .should().accessClassesThat().belongToAnyOf(StompEventType.class)
                .because("Cross-vocabulary translation (StatusMessage subtype → StompEventType) "
                        + "must only occur inside EventTypeMapping in the eventtype package "
                        + "(ADR-P3A-4). Any class outside eventtype that contains a method "
                        + "returning Optional and accesses StompEventType is performing "
                        + "an unauthorised ad-hoc translation.");
    }

    /**
     * Enforces that no production method outside {@code eventtype} returns {@link Optional}
     * AND accesses {@link StompEventType} enum constants (ADR-P3A-4).
     *
     * <p>Arrange: all production classes (src/main) in the Glacier package tree, loaded
     *            without test sources.
     * Act: apply the exclusivity ArchUnit rule.
     * Assert: no violation is found — no method outside {@code eventtype} has the forbidden
     *         translator-method shape.
     *
     * <p>This test turns RED if a developer re-introduces an ad-hoc translator method outside
     * {@code eventtype}, as demonstrated by the sibling regression test.
     */
    @Test
    void noMethodOutsideEventtypePackage_returnsOptionalAndAccessesStompEventType() {
        buildExclusivityRule().check(productionClasses);
    }

    /**
     * Regression proof: the ArchUnit rule correctly catches a deliberately violating method.
     *
     * <p>Arrange: load only {@link UnauthorisedTranslatorViolation} — a test-scope inner
     *            class with a method that returns {@link Optional} and accesses
     *            {@link StompEventType} outside the {@code eventtype} package.
     * Act: apply the exclusivity rule to this single violating class.
     * Assert: an {@link AssertionError} is thrown, and its message references ADR-P3A-4
     *         — proving the gate is live and would catch future violations.
     *
     * <p>Without this test, the production enforcement test could silently pass if the rule
     * definition were accidentally vacuous. This test makes the gate self-proving.
     */
    @Test
    void exclusivityRule_catchesDeliberateViolation() {
        // Load the deliberately violating inner class WITHOUT DO_NOT_INCLUDE_TESTS —
        // we explicitly WANT this test-scope class to prove the rule fires.
        JavaClasses violatingClass = new ClassFileImporter()
                .importClasses(UnauthorisedTranslatorViolation.class);

        ArchRule rule = buildExclusivityRule();

        // The rule MUST fire: UnauthorisedTranslatorViolation declares a method that returns
        // Optional AND accesses StompEventType constants outside the eventtype package.
        assertThatThrownBy(() -> rule.check(violatingClass))
                .as("ArchUnit exclusivity rule must catch UnauthorisedTranslatorViolation "
                        + "which illegally maps StatusMessage to StompEventType outside eventtype "
                        + "(ADR-P3A-4 regression proof)")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ADR-P3A-4");
    }

    /**
     * Documents that {@link EventTypeMapping} is the designated cross-vocabulary translator.
     *
     * <p>Arrange: all production classes.
     * Act: find {@link EventTypeMapping} in the loaded classes.
     * Assert: the class exists in the {@code eventtype} package — if this fails, the class
     * was moved or renamed without updating the ArchUnit gate.
     */
    @Test
    void eventTypeMappingClass_existsInEventtypePackage() {
        boolean found = productionClasses.stream()
                .anyMatch(cls -> cls.getFullName().equals(EventTypeMapping.class.getName()));
        assertThat(found)
                .as("EventTypeMapping must exist in the eventtype package (ADR-P3A-4); "
                        + "if this fails, the class was moved or renamed")
                .isTrue();
    }
}
