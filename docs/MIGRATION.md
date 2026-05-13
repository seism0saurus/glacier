# Glacier — Migration and Operational Notes

This file documents operational considerations for Glacier features that require explicit operator action when upgrading, rotating credentials, or recovering from failures.

## Contents

- [Share-link SQLite persistence](#share-link-sqlite-persistence)
  - [Backup procedure](#backup-procedure)
  - [Filesystem requirements](#filesystem-requirements)
  - [File permissions](#file-permissions)
  - [Schema changes and future migrations](#schema-changes-and-future-migrations)
  - [HMAC key rotation](#hmac-key-rotation)
  - [URL retrievability and recovery](#url-retrievability-and-recovery)

---

## Share-link SQLite persistence

The SQLite-backed share-link adapter is opt-in (see [`docs/ADMIN.md` — Share-link SQLite persistence](ADMIN.md#share-link-sqlite-persistence-opt-in)). Operators who activate it by setting `GLACIER_SHARE_DB_PATH` take on the following operational responsibilities.

### Backup procedure

SQLite in WAL (Write-Ahead Logging) mode — which Glacier enables via `PRAGMA journal_mode=WAL` — keeps two sidecar files alongside the main database file:

| File | Purpose |
|---|---|
| `<db>` | Main database file (e.g. `shares.db`) |
| `<db>-wal` | Write-ahead log; may contain committed data not yet checkpointed into the main file |
| `<db>-shm` | Shared-memory index for the WAL; required for WAL readers |

**All three files must be included in every backup** (SR-SQLITE-15). A backup of the main file alone may represent a partially-written state if it is taken while the application is running. The safest backup procedure is:

1. Stop the Glacier service (ensures a clean WAL checkpoint).
2. Copy all three files atomically (e.g. with `rsync --checksum` or a filesystem snapshot).
3. Restart the service.

If online (hot) backup is required without downtime, use the [SQLite Online Backup API](https://www.sqlite.org/backup.html) or the `sqlite3` CLI's `.backup` command, both of which handle WAL consistency automatically.

### Filesystem requirements

SQLite WAL mode requires exclusive advisory locks that are implemented via POSIX `fcntl` or Windows `LockFileEx`. These mechanisms do **not** work reliably on network-attached filesystems:

- NFS (any version): lock semantics are unreliable; WAL mode may corrupt the database silently.
- CIFS/SMB: same issue.
- Any FUSE-based remote mount (sshfs, rclone): same issue.

**The database file must be on a local filesystem** (ext4, XFS, tmpfs, APFS, NTFS — anything that provides POSIX advisory locks or Windows file locks natively). Glacier does **not** check this at startup — it is the operator's responsibility to ensure the path satisfies this requirement (SR-SQLITE-16).

In container deployments, use a `hostPath` volume (Kubernetes) or a Docker bind-mount to a directory on the node's local disk. Do not use an NFS-backed `PersistentVolumeClaim`.

### File permissions

On POSIX systems (Linux, macOS), Glacier creates the database file with permissions `0600` (owner read/write only). This matches the access-control requirement for secret-class files such as the Mastodon API token (`ACCESS_KEY`).

On non-POSIX systems (Windows), setting `0600` permissions at the Java level produces a `WARN` log entry because the underlying OS does not support POSIX permission bits. In that case, restrict access to the file manually using Windows ACLs before putting the instance into production:

```powershell
icacls "C:\glacier\shares.db" /inheritance:r /grant:r "$env:USERNAME:(R,W)"
```

Do not run Glacier in production on Windows without explicitly verifying that the database file is inaccessible to other OS users (SR-SQLITE-16).

### Schema changes and future migrations

The current adapter uses `CREATE TABLE IF NOT EXISTS` with no migration framework. This is intentional for the initial release — it keeps the dependency footprint minimal and avoids the complexity of Flyway or Liquibase when there is only one table version.

The current schema owns the following columns in the `share_links` table:

The table is declared `WITHOUT ROWID`, meaning SQLite stores rows in a B-tree keyed by the primary key (`id`) rather than by a hidden `rowid`. Tools and queries that assume a `rowid` column (e.g. `SELECT rowid, * FROM share_links`) will not work — always access rows via `id` or one of the indexes below.

| Column | Type | Nullable | Description |
|---|---|---|---|
| `id` | `TEXT` | NOT NULL (PK) | `SHA-256(raw_token)` encoded as lowercase hex. The raw token is **never stored**; a lost share URL cannot be reconstructed from this value. |
| `sharer_wall_id` | `TEXT` | NOT NULL | Wall-owner principal (wallId UUID cookie) |
| `creator_ip_hmac` | `TEXT` | nullable | `HMAC-SHA256(ip, key)` encoded as lowercase hex; `NULL` when the IP is unavailable or HMAC key is not configured |
| `created_at` | `INTEGER` | NOT NULL | Creation time as epoch milliseconds UTC |
| `expires_at` | `INTEGER` | NOT NULL | Expiry time as epoch milliseconds UTC |
| `revoked_at` | `INTEGER` | nullable | Revocation time as epoch milliseconds UTC; `NULL` means the link has not been revoked. This column, together with `expires_at`, is the authoritative source for link status — there is no separate `status` column. |

Three partial indexes exist on the table:

| Index name | Columns | Partial predicate | Purpose |
|---|---|---|---|
| `idx_share_links_sharer_active` | `(sharer_wall_id, expires_at)` | `WHERE revoked_at IS NULL` | Look up all active links for a given wall owner |
| `idx_share_links_creator_ip_active` | `(creator_ip_hmac, expires_at)` | `WHERE revoked_at IS NULL AND creator_ip_hmac IS NOT NULL` | Count active links from a given IP for the soft per-IP cap |
| `idx_share_links_expires_at` | `(expires_at)` | `WHERE revoked_at IS NULL` | Sweep expired-but-not-yet-revoked links during cleanup |

**If a schema change is ever needed** (new column, renamed column, index addition), introduce Flyway or Liquibase at that point. Do **not** add a column to an existing deployment by hand — doing so without a migration record will leave the schema in an unmanaged state that future tooling cannot reason about.

### HMAC key rotation

The `GLACIER_SHARE_DB_IP_HMAC_KEY` variable is used to compute a one-way `HMAC-SHA256(ip, key)` stored in the `creator_ip_hmac` column. This hash is used only for the soft per-IP active-link cap (`GLACIER_SHARE_MAX_ACTIVE_PER_IP`). It is a DoS-mitigation measure, not a hard security boundary.

**Effect of rotation:** When the key changes, all existing `creator_ip_hmac` values in the database become unrecognisable to the new key. `countActiveForIp()` returns 0 for every IP that created links before the rotation. The practical effect is that the per-IP cap resets — a user who already holds the maximum number of links can briefly create additional ones until those new links appear in the database under the new key.

**This is accepted behaviour.** The cap is advisory; it does not protect a cryptographic boundary. Rotating the key does not compromise link validity, token secrecy, or any other security property.

**Rotation procedure:**

1. Generate a new cryptographically random key (minimum 32 random bytes, base64-encoded to ≥ 44 characters):
   ```bash
   openssl rand -base64 32
   ```
2. Update `GLACIER_SHARE_DB_IP_HMAC_KEY` in your environment configuration or secrets manager.
3. Restart the Glacier service. No database rebuild or data migration is required.
4. Existing share links remain fully valid. Only the per-IP cap tracking resets.

**Key storage:** Treat the HMAC key with the same care as `ACCESS_KEY`. Store it in a secrets manager or a vault-encrypted config file — never in a public repository or an unencrypted environment file.

### URL retrievability and recovery

Glacier stores only `SHA-256(token)` in the database, never the raw share-link token. The raw token is generated once in memory, returned to the creator as part of the share URL, and then discarded. **A lost share URL cannot be recovered from the database.**

Operators and users should be aware of the following:

- The full share URL (including the raw token) is displayed **once** at creation time. Instruct users to save it immediately.
- Management endpoints (e.g. the list of active share links) return only a truncated identifier (`idHash8` — the first 8 hex characters of the SHA-256 hash), sufficient to identify which link to revoke but not to reconstruct the URL.
- If a user loses their share URL, the only recovery path is:
  1. Identify the link by its creation time or `idHash8` in the management UI.
  2. Revoke it.
  3. Create a new share link and send the new URL to the intended viewers.

This is an intentional security property: the database cannot be exfiltrated to reconstruct live share URLs (SR-SQLITE-21).
