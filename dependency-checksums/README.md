# dependency-checksums

This directory contains SHA-256 checksums for third-party JAR dependencies that are not
managed by the Spring Boot BOM and carry supply-chain risk.  Both CI workflows
(`verify.yml` and `pull-request.yml`) download each listed JAR from Maven Central and
verify it against the corresponding `.sha256` file **before** the Maven build runs.  A
checksum mismatch fails the workflow immediately.

## Files

| File | Dependency | SHA-256 |
|------|------------|---------|
| `sqlite-jdbc-3.49.1.0.sha256` | `org.xerial:sqlite-jdbc:3.49.1.0` | `5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291` |

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
