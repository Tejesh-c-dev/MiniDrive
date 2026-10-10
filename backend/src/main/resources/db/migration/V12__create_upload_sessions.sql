-- Phase 8.2: persistent, resumable chunked-upload sessions.
--
-- An upload_sessions row describes one in-progress chunked upload owned by a
-- single user. The declared metadata (filename, content type, total size) is
-- recorded up front so the server can pick a chunk size and derive an exact
-- chunk count; the client is told both and can then PUT each chunk
-- independently. folder_id records the destination folder for a brand-new
-- file, while target_file_id records the file whose content is being replaced
-- (both optional and both resolved against the authenticated owner's own
-- rows). Neither is assembled into a File/FileVersion here -- finalization is
-- Phase 8.4 -- so this migration never touches the files or file_versions
-- tables.
--
-- upload_chunks rows are one staging object per uploaded chunk. Chunk bytes
-- are written to object storage first and a row is inserted only after that
-- write succeeds, so a row always points at bytes that exist. The
-- (session_id, chunk_index) pair is unique, which makes a retry of the same
-- chunk idempotent instead of producing duplicates, while distinct indexes
-- remain independently uploadable. staging_object_key is server-generated
-- under a reserved "_uploads/" prefix and is never exposed to clients.
--
-- V11__create_file_versions.sql is already applied and must not be modified.

CREATE TABLE upload_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID NOT NULL,
    folder_id         UUID,
    target_file_id    UUID,
    filename          VARCHAR(255) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    total_size_bytes  BIGINT NOT NULL,
    chunk_size_bytes  INTEGER NOT NULL,
    total_chunks      INTEGER NOT NULL,
    status            VARCHAR(20) NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    expires_at        TIMESTAMP NOT NULL,

    CONSTRAINT fk_upload_sessions_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT fk_upload_sessions_folder FOREIGN KEY (folder_id)
        REFERENCES folders (id) ON DELETE CASCADE,

    CONSTRAINT fk_upload_sessions_target_file FOREIGN KEY (target_file_id)
        REFERENCES files (id) ON DELETE CASCADE,

    CONSTRAINT ck_upload_sessions_status
        CHECK (status IN ('INITIATED', 'COMPLETED', 'ABORTED', 'EXPIRED')),

    CONSTRAINT ck_upload_sessions_total_size_non_negative
        CHECK (total_size_bytes >= 0),

    CONSTRAINT ck_upload_sessions_chunk_size_positive
        CHECK (chunk_size_bytes > 0),

    -- A zero-byte file still has exactly one (empty) chunk, so the count is
    -- never zero and the last-chunk size formula stays well defined.
    CONSTRAINT ck_upload_sessions_total_chunks_positive
        CHECK (total_chunks >= 1)
);

CREATE INDEX idx_upload_sessions_owner ON upload_sessions (owner_id);
CREATE INDEX idx_upload_sessions_expires_at ON upload_sessions (expires_at);

CREATE TABLE upload_chunks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL,
    chunk_index         INTEGER NOT NULL,
    size_bytes          BIGINT NOT NULL,
    checksum            VARCHAR(128),
    staging_object_key  VARCHAR(512) NOT NULL,
    created_at          TIMESTAMP NOT NULL,

    CONSTRAINT fk_upload_chunks_session FOREIGN KEY (session_id)
        REFERENCES upload_sessions (id) ON DELETE CASCADE,

    CONSTRAINT uq_upload_chunks_session_index UNIQUE (session_id, chunk_index),

    CONSTRAINT uq_upload_chunks_staging_object_key UNIQUE (staging_object_key),

    CONSTRAINT ck_upload_chunks_index_non_negative CHECK (chunk_index >= 0),

    CONSTRAINT ck_upload_chunks_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_upload_chunks_session ON upload_chunks (session_id);
