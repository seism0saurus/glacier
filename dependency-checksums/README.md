# Dependency Checksums

This directory contains SHA-256 checksums for dependencies that carry elevated
supply-chain risk — specifically, JARs that bundle native code (e.g., SQLite C
binaries) where artifact mutation would grant host-level code execution.

## Verification

Both CI workflows (`verify.yml` and `pull-request.yml`) run `sha256sum -c` against
these files **before** any Maven build step. A mismatch fails the pipeline
immediately.

To verify locally from the repo root:

```bash
sha256sum -c dependency-checksums/sqlite-jdbc-3.49.1.0.sha256
```

## Pinned artifacts

| File | Artifact | Version | Rationale |
|------|----------|---------|-----------|
| `sqlite-jdbc-3.49.1.0.sha256` | `org.xerial:sqlite-jdbc` | 3.49.1.0 | Bundles native SQLite C code (SR-SQLITE-12) |

## Updating a pin

When upgrading a pinned dependency:

1. Copy the new JAR to a temp directory:
   ```bash
   ./mvnw dependency:copy -Dartifact=org.xerial:sqlite-jdbc:<NEW_VERSION>:jar \
     -DoutputDirectory=/tmp/sqlite-jdbc-pin
   ```
2. Compute the checksum:
   ```bash
   sha256sum /tmp/sqlite-jdbc-pin/sqlite-jdbc-<NEW_VERSION>.jar
   ```
3. Create `dependency-checksums/sqlite-jdbc-<NEW_VERSION>.sha256` with the output.
4. Update the `sha256sum -c` step in both workflow files to reference the new filename.
5. Remove the old `.sha256` file.
6. Commit all three changes together.
