-- Phase 7.1: immutable per-file content versions.
--
-- A file_versions row is an append-only snapshot of one stored content
-- revision. The parent files row stays the mutable "current" record: its
-- object_key/content_type/size_bytes always describe the newest version, so
-- existing downloads and share links are unaffected. Every version keeps its
-- own storage object key, and replacement writes a brand new key instead of
-- overwriting a previous one, so historical bytes are never destroyed.
--
-- version_number starts at 1 and the (file_id, version_number) pair is unique
-- so the same revision can never be recorded twice. created_by records the
-- collaborator who uploaded the revision, which may differ from the owner.
-- Cascading deletes keep rows consistent: removing a file (or a user) removes
-- the version rows that point at it. Historical storage objects are retained
-- by the application layer; this migration never deletes object bytes.

CREATE TABLE file_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id           UUID NOT NULL,
    version_number    INTEGER NOT NULL,
    object_key        VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    created_by        UUID NOT NULL,
    created_at        TIMESTAMP NOT NULL,

    CONSTRAINT fk_file_versions_file FOREIGN KEY (file_id)
        REFERENCES files (id) ON DELETE CASCADE,

    CONSTRAINT fk_file_versions_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT uq_file_versions_file_version UNIQUE (file_id, version_number),

    CONSTRAINT uq_file_versions_object_key UNIQUE (object_key),

    CONSTRAINT ck_file_versions_version_positive CHECK (version_number >= 1),

    CONSTRAINT ck_file_versions_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_file_versions_file ON file_versions (file_id);
CREATE INDEX idx_file_versions_created_by ON file_versions (created_by);
