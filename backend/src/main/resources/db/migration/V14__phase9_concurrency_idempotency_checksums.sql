-- Phase 9: serialize concurrent metadata changes, persist content hashes, and
-- make upload-session creation retryable by caller-provided idempotency key.
ALTER TABLE files ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE upload_sessions
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN create_idempotency_key VARCHAR(200),
    ADD COLUMN create_request_hash VARCHAR(64);

CREATE UNIQUE INDEX uq_upload_sessions_owner_idempotency
    ON upload_sessions (owner_id, create_idempotency_key)
    WHERE create_idempotency_key IS NOT NULL;

ALTER TABLE file_versions ADD COLUMN checksum VARCHAR(64);

-- Content-addressed physical deduplication is deferred. The current deletion
-- lifecycle assumes each logical version owns its object; sharing objects needs
-- transactional reference tracking before it can be safe.
