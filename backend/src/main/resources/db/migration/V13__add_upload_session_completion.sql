-- Phase 8.4: finalization bookkeeping for chunked upload sessions.
--
-- A chunked upload becomes a real File (and FileVersion) only when it is
-- explicitly completed. To make completion idempotent, the session records the
-- file and version it produced, so a repeated completion returns the same
-- logical result instead of creating a second file or version. These columns
-- stay NULL until completion succeeds and are written in the same transaction
-- as the File/FileVersion rows, so a completed session can never point at a
-- partially persisted result.
--
-- completed_file_id intentionally uses ON DELETE SET NULL: if the produced file
-- is later deleted, the session row survives as a tombstone (status COMPLETED)
-- rather than disappearing, which keeps repeat-completion from silently
-- recreating content that was deliberately removed.
--
-- V12__create_upload_sessions.sql is already applied and must not be modified.

ALTER TABLE upload_sessions
    ADD COLUMN completed_file_id UUID,
    ADD COLUMN completed_version_number INTEGER,
    ADD COLUMN completed_at TIMESTAMP;

ALTER TABLE upload_sessions
    ADD CONSTRAINT fk_upload_sessions_completed_file
        FOREIGN KEY (completed_file_id)
        REFERENCES files (id) ON DELETE SET NULL;

ALTER TABLE upload_sessions
    ADD CONSTRAINT ck_upload_sessions_completed_version_positive
        CHECK (completed_version_number IS NULL OR completed_version_number >= 1);
