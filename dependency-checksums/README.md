# dependency-checksums

This directory contains SHA-256 checksums for third-party JAR dependencies that are not
managed by the Spring Boot BOM and carry supply-chain risk.  Both CI workflows
(`verify.yml` and `pull-request.yml`) verify each listed JAR against its corresponding
`.sha256` file **before** the Maven build runs; a checksum mismatch fails the workflow
immediately.  All pinned artifacts are stable Maven Central releases: the JAR is resolved
(via URL download or `dependency:get`) and checked with `sha256sum -c`.

## Files

| File | Dependency | SHA-256 |
|------|------------|---------|
| `sqlite-jdbc-3.49.1.0.sha256` | `org.xerial:sqlite-jdbc:3.49.1.0` | `5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291` |
| `bigbone-2.0.0.sha256` | `io.github.pattafeufeu:bigbone:2.0.0` (released 2026-06-15) | `d342be95506e46c1f1e64269ea2e95b5fa14374fe50de6b894f760df338cb5fd` |

> **History (ADR-SEC-02):** until 2026-07, `bigbone` was consumed as the mutable
> `social.bigbone:bigbone:2.0.0-SNAPSHOT` — the one sanctioned SNAPSHOT exception — and this
> directory held a *tripwire* hash that turned silent upstream republishing into a loud,
> human-gated build failure. Upstream moved to `io.github.pattafeufeu`
> (https://github.com/PattaFeuFeu/bigbone), tagged the stable `2.0.0` release on 2026-06-15
> and discontinued SNAPSHOT publishing, so `bigbone` is now a normal immutable release pin
> like `sqlite-jdbc`, and the `requireReleaseDeps` enforcer rule in `pom.xml` applies with
> no excludes. The structural gate `BigboneChecksumPinTest` (in `src/test/java/.../ci/`)
> keeps the CI verification step from regressing.

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

## Format

Each `.sha256` file follows the `sha256sum` two-field format:

```
<hex-digest>  <filename>
```

Two spaces between the digest and the filename — this is required by `sha256sum -c`.
The JAR file must be downloaded to the same directory as the `.sha256` file before running
`sha256sum -c`.
