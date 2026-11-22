# MD-008 historical object retention and MD-009 reconciliation

## Retention policy and consistency invariants

- A current object referenced by `files.object_key` is retained and must resolve
  in its intended storage configuration.
- Every historical object referenced by `file_versions.object_key` is retained
  and must resolve. Replacement and restore write fresh keys.
- Active upload staging objects remain owned by their upload chunk/session
  manifest and are not orphan candidates.
- Permanent deletion commits the exact current, historical, and staged key
  manifest before metadata cascades or storage deletion. A replica
  `DELETING` tombstone means intentional retirement and must block replica repair.
- Logical references, physical replicas, staging content, historical versions,
  and pending lifecycle operations are distinct categories. A physical replica
  row alone does not establish a live logical reference.
- An unavailable database, replica, or object store is an inspection failure,
  never proof that content is absent or unreferenced. Unknown objects are never
  deleted by reconciliation.

## Bounded report-only reconciliation

The scheduler performs bounded queries and does not scan whole buckets or
mutate storage or metadata. It reuses the MD-008 orphan report and its grace
period. It reports old `object_locations` routes with no current file, retained
version, upload chunk, deletion manifest, or independent cleanup-journal intent;
live file/version references without route registration; orphaned non-deleting
replica rows; and old folder deletion or upload cleanup operations.

Statuses are based on their observed evidence:

- `UNREFERENCED_REGISTERED_OBJECT`: an aged route has no known logical or
  pending-operation reference. It says nothing about physical Silo content.
- `MISSING_ROUTE_REGISTRATION`: live PostgreSQL metadata has no route row. This
  does not prove that physical bytes are missing.
- `STALE_REPLICA_STATE`: a replica row has no live metadata or pending deletion
  manifest. `DELETING` rows are excluded because they are intentional tombstones.
- `PENDING_OPERATION`: a durable lifecycle row is older than the threshold; it
  does not prove a worker is dead.
- `INSPECTION_FAILED`: a database inspection failed, so classification is
  inconclusive.
- `MISSING_OBJECT`: the Silo adapter's metadata-only `statObject` check returned
  an explicit not-found response for the registered authoritative node.
  `INSPECTION_FAILED` is used for outages, authorization/configuration errors,
  unsupported probes, and other inconclusive results. The check is bounded by
  the live-reference batch size and downloads no content.

Unknown namespaces are retained for review. `object_locations` is an application
route table, not a complete Silo inventory. Unregistered Silo objects and
routes missing after an earlier transaction failure are not discoverable by
this report. No automatic orphan collection is implemented or enabled.

## Cross-store recovery limits

Upload, replacement, and restore write object content before metadata commits.
They attempt compensation and preserve failed cleanup in the durable folder
deletion outbox or independent cleanup-intent journal. If both PostgreSQL and
the filesystem journal are unavailable when compensation fails, only an
operator log remains. There is no durable record of the intended metadata
result for an uncommitted write, so the report cannot safely adopt it.

Deletion commits its manifest before remote deletion. If remote deletion
succeeds but marking the item deleted fails, retrying the idempotent delete
finishes the transition. Routing writes per-replica `DELETING` tombstones before
physical removal so repair will not resurrect intentionally deleted objects.

Folder deletion and expired-upload cleanup do not fence storage I/O with a
lease token. Two workers can issue duplicate deletes, which are expected to be
idempotent. If a worker outlives an upload cleanup lease, the replacement worker
may overlap it. Reconciliation reports old work but does not claim or repair it.
These races remain limitations. Replica state is reported from database
metadata; the report does not stat every replica or establish complete physical
inventory.

## MD-009 verification boundaries

The write-ahead journal is a local filesystem mechanism, not a distributed
database. Intent contents and the lock-file channel are forced where the
supported filesystem permits it. On Windows, the standard JDK provider cannot
open a directory handle for `fsync`; the intent file is forced, but directory
entry durability after power loss depends on the mounted volume. On Linux,
the deployment filesystem must support directory `fsync`.

Every application instance and cleanup worker must use the same durable journal
directory (or a shared volume with reliable cross-process locking). If
instances use different directories, each can miss the other's pending
intents. An ephemeral or lost journal volume can make an object untraceable
after both the database compensation path and storage write have diverged.
Journal write failures are surfaced to the operation and are not converted to
successful storage writes. Malformed and unreadable entries remain in place and
are reported as `INSPECTION_FAILED` so operators retain the evidence.

The completed live evidence is intentionally separated by infrastructure:

- H2 tests cover state transitions, replay reference protection, bounded
  report logic, and duplicate-worker convergence.
- Opt-in PostgreSQL tests use a disposable database for migration behavior and
  genuine reference-query failures; a failed query is classified as
  `INSPECTION_FAILED`, never as an empty reference set.
- Opt-in PostgreSQL + Silo tests use disposable object prefixes and exercise
  authoritative not-found, authentication, endpoint-unavailable, and
  replica-deletion behavior.
- The current worker lease permits overlapping cleanup after expiry. Deletes
  are idempotent and the final state transition rechecks the durable status,
  but there is no fencing token that proves the caller still owns the lease.
  This remains a correctness limitation and is not reported as crash or
  cross-host recovery evidence.

MD-009 therefore remains `PARTIAL` until independent-worker tests cover the
folder-deletion versus expired-upload race and replacement/restoration versus
cleanup recovery with real PostgreSQL + Silo services, and until the deployment
can provide the journal-volume and lease-fencing guarantees required by its
operational model. Reconciliation remains bounded, repeatable, and
report-only; it never mutates storage or metadata.
