package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * ADR-SEC-02 supply-chain gate, release-path scope: {@code build-and-deploy.yml} -- the single
 * privileged workflow that publishes the signed {@code ghcr.io/seism0saurus/glacier} image and
 * triggers production deploy (ADR-CI-01) -- must verify the sqlite-jdbc / bigbone SHA-256
 * checksum tripwire (the same one {@link BigboneChecksumPinTest} pins for {@code _build.yml})
 * before it builds the artifact that ends up signed, attested, and shipped.
 *
 * <h2>Why this gate exists (Phase 2 Round 2 cross-review finding)</h2>
 * {@link BigboneChecksumPinTest#buildCoreVerifiesBigboneChecksumBeforeBuilding()} and
 * {@link BigboneChecksumPinTest#pullRequestWorkflowDelegatesBuildToSharedCoreInsteadOfDuplicatingIt()}
 * only guard that {@code _build.yml} (and callers that delegate to it, like
 * {@code pull-request.yml}) run the checksum tripwire. Before Lane C's fix,
 * {@code build-and-deploy.yml} declared its own independent {@code compile}/{@code test}/
 * {@code package} jobs and never ran {@code sha256sum -c sqlite-jdbc-3.49.1.0.sha256} or
 * {@code sha256sum -c bigbone-2.0.0.sha256} anywhere -- the ONE pipeline that actually pushes a
 * cosign-signed, SLSA-attested image to the registry and triggers the SSH deploy was the ONE
 * pipeline that skipped the dependency-substitution tripwire that runs on every PR and every
 * quality-gate push. A registry/proxy-level swap of the sqlite-jdbc or bigbone jar timed to land
 * on a tag push would have sailed through unchecked -- silently included under the very cosign
 * signature and SLSA provenance attestation that downstream consumers rely on for integrity
 * assurance (OWASP A08:2021 -- Software and Data Integrity Failures; OWASP A06:2021 --
 * Vulnerable and Outdated Components).
 *
 * <h2>Fix landed (Phase 2 Round 2)</h2>
 * Lane C consolidated {@code build-and-deploy.yml}'s {@code test} job to call {@code _build.yml}
 * via {@code uses:} (G6), so it now inherits the checksum tripwire for free -- the same
 * acceptance shape {@code BigboneChecksumPinTest} already uses for {@code pull-request.yml}.
 * This test is the permanent regression gate for that fix: it stays GREEN as long as the
 * release path either delegates to {@code _build.yml} or (fallback branch, if delegation is
 * ever reverted) runs both checksum steps itself, strictly before the Maven build.
 *
 * <p>Mode applicability: mode-agnostic -- supply-chain integrity is independent of Glacier's
 * operational mode (live / fallback / killswitch / insecure).
 *
 * @see <a href="https://owasp.org/Top10/A08_2021-Software_and_Data_Integrity_Failures/">OWASP A08:2021</a>
 * @see <a href="https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/">OWASP A06:2021</a>
 */
class BuildAndDeployChecksumTripwireTest {

    private static final String DEPLOY_WORKFLOW = "build-and-deploy.yml";
    private static final String BUILD_CORE_WORKFLOW = "_build.yml";
    private static final String DEPLOY_TEST_JOB_ID = "test";

    private static final String SQLITE_CHECKSUM_FILE = "sqlite-jdbc-3.49.1.0.sha256";
    private static final String BIGBONE_CHECKSUM_FILE = "bigbone-2.0.0.sha256";
    private static final String SHA256SUM = "sha256sum";
    private static final String MAVEN_VERIFY = "mvnw --batch-mode verify";

    @Test
    void releasePathTestJobDelegatesToBuildCoreOrRunsTheChecksumTripwireItselfBeforeBuilding() {
        WorkflowFile deploy = WorkflowInventory.loadWorkflow(DEPLOY_WORKFLOW);
        WorkflowJob testJob = deploy.job(DEPLOY_TEST_JOB_ID)
                .orElseThrow(() -> new AssertionError(
                        DEPLOY_WORKFLOW + " must define a '" + DEPLOY_TEST_JOB_ID + "' job"));

        SoftAssertions softly = new SoftAssertions();

        if (testJob.callsReusableWorkflow()) {
            // Consolidation fix (G6): delegating to _build.yml inherits its checksum tripwire
            // for free -- same acceptance shape BigboneChecksumPinTest already uses for
            // pull-request.yml.
            softly.assertThat(testJob.calledWorkflow())
                    .as("%s's '%s' job calls a reusable workflow but it must be %s specifically "
                            + "so it inherits the checksum tripwire (ADR-SEC-02)",
                            DEPLOY_WORKFLOW, DEPLOY_TEST_JOB_ID, BUILD_CORE_WORKFLOW)
                    .contains(BUILD_CORE_WORKFLOW);
        } else {
            List<WorkflowStep> runSteps = testJob.runSteps();

            int sqliteIdx = indexOfFirstRunContaining(runSteps, SQLITE_CHECKSUM_FILE, SHA256SUM);
            int bigboneIdx = indexOfFirstRunContaining(runSteps, BIGBONE_CHECKSUM_FILE, SHA256SUM);
            int buildIdx = indexOfFirstRunContaining(runSteps, MAVEN_VERIFY);

            softly.assertThat(sqliteIdx)
                    .as("%s's '%s' job must run the sqlite-jdbc SHA-256 checksum tripwire "
                            + "(sha256sum -c %s) before publishing a signed release image "
                            + "(ADR-SEC-02) -- this is the one pipeline that ships a "
                            + "cosign-signed image, so it must not be the one pipeline that "
                            + "skips dependency-substitution verification",
                            DEPLOY_WORKFLOW, DEPLOY_TEST_JOB_ID, SQLITE_CHECKSUM_FILE)
                    .isGreaterThanOrEqualTo(0);

            softly.assertThat(bigboneIdx)
                    .as("%s's '%s' job must run the bigbone SHA-256 checksum tripwire "
                            + "(sha256sum -c %s) before publishing a signed release image "
                            + "(ADR-SEC-02)", DEPLOY_WORKFLOW, DEPLOY_TEST_JOB_ID, BIGBONE_CHECKSUM_FILE)
                    .isGreaterThanOrEqualTo(0);

            softly.assertThat(buildIdx)
                    .as("%s's '%s' job must contain the Maven verify build step whose ordering "
                            + "relative to the checksum tripwire this gate protects",
                            DEPLOY_WORKFLOW, DEPLOY_TEST_JOB_ID)
                    .isGreaterThanOrEqualTo(0);

            if (sqliteIdx >= 0 && buildIdx >= 0) {
                softly.assertThat(sqliteIdx)
                        .as("%s: the sqlite-jdbc checksum tripwire must run BEFORE the Maven "
                                + "build so a mutated dependency is caught before its code is "
                                + "executed and before it can end up in the artifact that gets "
                                + "signed and shipped", DEPLOY_WORKFLOW)
                        .isLessThan(buildIdx);
            }
            if (bigboneIdx >= 0 && buildIdx >= 0) {
                softly.assertThat(bigboneIdx)
                        .as("%s: the bigbone checksum tripwire must run BEFORE the Maven build "
                                + "so a mutated dependency is caught before its code is executed "
                                + "and before it can end up in the artifact that gets signed and "
                                + "shipped", DEPLOY_WORKFLOW)
                        .isLessThan(buildIdx);
            }
        }

        softly.assertAll();
    }

    private static int indexOfFirstRunContaining(List<WorkflowStep> steps, String... allOf) {
        for (int i = 0; i < steps.size(); i++) {
            String run = steps.get(i).run();
            boolean all = true;
            for (String needle : allOf) {
                if (run == null || !run.contains(needle)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return i;
            }
        }
        return -1;
    }
}
