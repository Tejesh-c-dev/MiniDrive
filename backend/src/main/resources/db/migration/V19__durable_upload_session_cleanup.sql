-- Durable cleanup claims let workers recover expired-session cleanup after a
-- process crash while preventing other instances from concurrently deleting
-- the same staging manifest. A stale CLEANING row is eligible for lease retry.
ALTER TABLE upload_sessions
    DROP CONSTRAINT ck_upload_sessions_status;

ALTER TABLE upload_sessions
    ADD CONSTRAINT ck_upload_sessions_status
        CHECK (status IN ('INITIATED', 'COMPLETED', 'ABORTED', 'EXPIRED', 'CLEANING'));

CREATE INDEX idx_upload_sessions_cleanup
    ON upload_sessions (status, updated_at, expires_at);
