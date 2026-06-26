# dependency-checksums

This directory contains SHA-256 checksums for third-party JAR dependencies that are not
managed by the Spring Boot BOM and carry supply-chain risk.  Both CI workflows
(`verify.yml` and `pull-request.yml`) verify each listed JAR against its corresponding
`.sha256` file **before** the Maven build runs; a checksum mismatch fails the workflow
immediately.  Two patterns are used:

- **Stable releases** (e.g. `sqlite-jdbc`) are downloaded from Maven Central by URL and
  checked with `sha256sum -c`.
- **The sanctioned `bigbone` SNAPSHOT** is resolved via Maven (`dependency:get`) and the
  hash of the resolved jar is compared — a *tripwire* that detects upstream mutation of a
  dependency that has no stable release (see the dedicated note below).

## Files

| File | Dependency | SHA-256 |
|------|------------|---------|
| `sqlite-jdbc-3.49.1.0.sha256` | `org.xerial:sqlite-jdbc:3.49.1.0` | `5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291` |
| `bigbone-2.0.0-SNAPSHOT.sha256` | `social.bigbone:bigbone:2.0.0-SNAPSHOT` (build `-33`, 2025-04-27) | `b02342e02d385a6b85c3a374c10c54a5310294ce29850b4e58e37a8f8a3b1fe2` |

> **`bigbone` is a SNAPSHOT tripwire, not a stable pin.** Unlike the Maven Central JARs above, `bigbone:2.0.0-SNAPSHOT` is mutable — upstream may republish it with new content at any time (there is no tagged release; see ADR-SEC-02 and the comment in `pom.xml`). The CI step resolves the SNAPSHOT and compares its hash so that a silent upstream change becomes a **loud, human-gated build failure** rather than an invisible supply-chain swap. When the tripwire fires, do **not** blindly update the hash: review the upstream change first, then re-pin per the procedure below.

## Upgrade procedure

1. Update the `<version>` in `pom.xml` for the dependency being upgraded.
2. Download the new JAR from Maven Central:
   ```
   curl -fsSL -o sqlite-jdbc-<NEW_VERSION>.jar \
     https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/<NEW_VERSION>/sqlite-jdbc-<NEW_VERSION>.jar
   ```
3. Compute its SHA-256:
   ```
   sha256sum sqlite-jdbc-<NEW_VERSION>.jar
   ```
4. Create a new checksum file `sqlite-jdbc-<NEW_VERSION>.sha256` with the content:
   ```
   <HASH>  sqlite-jdbc-<NEW_VERSION>.jar
   ```
5. Delete the old checksum file.
6. Update the CI step name and download URL in both `.github/workflows/verify.yml` and
   `.github/workflows/pull-request.yml` to reference the new version.
7. Update the table in this README.
8. Commit all four changed files together.

## Re-pinning the `bigbone` SNAPSHOT tripwire

When the "Verify bigbone snapshot SHA-256" CI step fails, upstream republished the SNAPSHOT.
**Review the upstream diff before trusting the new artifact** (https://github.com/andregasser/bigbone).
Once reviewed and trusted:

1. Force-resolve the current SNAPSHOT into the local repo and read its hash:
   ```
   ./mvnw -q dependency:get -Dartifact=social.bigbone:bigbone:2.0.0-SNAPSHOT -U
   sha256sum ~/.m2/repository/social/bigbone/bigbone/2.0.0-SNAPSHOT/bigbone-2.0.0-SNAPSHOT.jar
   ```
2. Replace the digest in `bigbone-2.0.0-SNAPSHOT.sha256` (keep the `  bigbone-2.0.0-SNAPSHOT.jar` suffix).
3. Update the build number/date and the SHA-256 in the table above.
4. Commit the checksum file + README together, noting the reviewed upstream change in the message.

No CI-workflow edit is needed for a re-pin (the step reads the file). The structural gate
`BigboneSnapshotTripwireTest` (in `src/test/java/.../ci/`) keeps the step itself from regressing.
When upstream finally tags a stable release, follow the standard upgrade procedure instead and
remove this tripwire (pin the release version + add a `requireReleaseDependencies` enforcer rule).

## Format

Each `.sha256` file follows the `sha256sum` two-field format:

```
<hex-digest>  <filename>
```

Two spaces between the digest and the filename — this is required by `sha256sum -c`.
The JAR file must be downloaded to the same directory as the `.sha256` file before running
`sha256sum -c`.
