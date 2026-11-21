# Local V20/V21 migration recovery record

## Target and preflight

This recovery is restricted to the local PostgreSQL 16 development database at
`jdbc:postgresql://127.0.0.1:55432/minidrive`, running as database role
`minidrive`. Production and all other targets are excluded.

Before the first Flyway command, every tracked V1–V21 source checksum was
calculated using Flyway 12.4.0's line-ending-independent CRC32 algorithm and
compared with the database migration history. V1–V19 matched successful rows.
V20 is the only applied checksum mismatch: history records `191376876`, while
the current tracked, unchanged V20 file resolves to `-1683305556`. V21 resolves
to `191149905` and is pending. The database reports successful versions 1–20.

Catalog inspection confirmed `revoked_tokens` matches V20's current table,
primary key, and expiry index. The existing `refresh_tokens` table matches V21's
seven column types, nullability, and defaults, primary key, validated cascading
foreign key to `users(id)`, and three named indexes. No V21 history row exists.
These matches establish current schema shape, not the historical provenance of
the SQL that created those objects.

## Backup and test evidence

- Backup: `%USERPROFILE%\Documents\MiniDrive\migration-backups\minidrive-dev-20261121-pg16-214108.dump`
- Format: PostgreSQL 16 custom archive; SHA-256:
  `30DF002ACA3BE52CA55AE13D49AAED70078D16EC46C5D98AE721E85E10A7042E`
- Restore verification: successful restore into a temporary database on an
  isolated PostgreSQL 16 server, followed by checks for the 20-row migration
  history, 15 public tables, and four refresh-token indexes. The temporary
  restored database was removed after verification.
- V21 isolated PostgreSQL migration tests: 8 run, 8 passed (fresh, matching
  pre-provisioned, partial, and incompatible type, default, primary key, foreign
  key, and index definitions).

## V20 reconciliation

After the checksum and schema preflight, verified backup, and isolated V21
tests, Flyway `repair` was run against the explicit development URL. Its sole
metadata change was V20's checksum, from `191376876` to `-1683305556`; no
migration source or application schema object was changed by repair. This is an
accepted metadata reconciliation to the current tracked file, not recovery or
certification of the original V20 SQL. The original V20 text remains
unavailable, and matching catalog effects do not prove byte-for-byte or
semantic identity with that historical source.

V21 was then applied to the same explicit development target. Its exact
matching refresh-token objects were validated and reused. Flyway reported one
successful migration applied (V21).

## Final verification

Flyway `validate` passed all 21 migrations. Flyway `info` reports versions
1–21 successful, with V20 checksum `-1683305556` and V21 checksum `191149905`.
The database history contains exactly the expected successful V1–V21 rows.

The final catalog inspection confirmed:

- V20: `revoked_tokens.jti` is `varchar(36) NOT NULL` and the primary key;
  `expires_at` is `timestamptz NOT NULL`; `idx_revoked_tokens_expires_at`
  indexes `expires_at`.
- V21: all seven columns have the expected PostgreSQL types, nullability, and
  `created_at DEFAULT CURRENT_TIMESTAMP`; the primary key is on `token_hash`;
  the validated `user_id` foreign key references `users(id) ON DELETE CASCADE`;
  the family, user, and expiry indexes are present.

Verification counts: eight V21 PostgreSQL migration tests passed; 21 auth tests
passed (`AuthControllerTest` 20, `JwtAuthenticationFilterRevocationTest` 1).
V1–V20 migration files were not modified. No clean or baseline was run.

## Remaining provenance limitations

The historical SQL text that produced the old V20 checksum could not be
recovered. Flyway repair reconciled metadata to the current tracked V20 file
after the known catalog comparison; this does not establish what the old SQL
contained. Likewise, the existing refresh-token objects had no V21 history row
before this recovery, and their original creator remains unknown. Production
state was not inspected and no production action was taken.
