# Custom application metrics

MiniDrive exports these application metrics through the already enabled
`/actuator/prometheus` endpoint. Durations are measured in seconds; byte totals
are measured in bytes.

| Metric | Type | Bounded labels | Meaning |
| --- | --- | --- | --- |
| `minidrive_file_operation_seconds` | Timer | `operation`, `outcome` | Upload, replacement, restoration, deletion, and authenticated or public-share download method duration. Timer count is the operation count. |
| `minidrive_storage_node_operation_seconds` | Timer | `operation`, `outcome`, `node_id` | Actual routed storage-node upload and delete call duration, plus download duration through EOF or stream close. |
| `minidrive_storage_node_health` | Gauge | `node_id` | Configured node and bucket availability: `1` healthy, `0` unhealthy, `-1` before the first probe. Probes run every 15 seconds by default. |
| `minidrive_storage_node_bytes_total` | Counter | `operation`, `node_id` | Successful known-size upload bytes and bytes read from node download streams. |
| `minidrive_upload_session_operation_seconds` | Timer | `operation`, `outcome` | Session creation, chunk staging, completion, and abort duration. Timer count is the attempt count. |
| `minidrive_upload_session_events_total` | Counter | `event`, `outcome` | Distinct idempotent retries, checksum failures, and staging object cleanup outcomes. |

The current event values are `idempotent_retry`, `checksum_failure`, and
`staging_cleanup`; outcomes are `success` and `failure`. Metric labels never
contain request, file, user, object, session, URL, or exception data. No
histograms are enabled for these timers, avoiding bucket series multiplication.
The node ID comes from the configured storage-node map. Download bytes are
counted only as callers consume the returned stream; opening a stream alone
records no bytes or completed download duration. Closing before EOF records a
failed or interrupted node download outcome.
