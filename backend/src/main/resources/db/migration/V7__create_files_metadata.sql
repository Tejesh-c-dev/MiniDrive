-- Phase 3: file metadata only. Actual object bytes (MinIO) are Phase 4.
-- V3__create_files.sql is an existing empty placeholder and must not be modified.

CREATE TABLE files (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID NOT NULL,
    folder_id     UUID,
    name          VARCHAR(255) NOT NULL,
    object_key    VARCHAR(512) NOT NULL,
    content_type  VARCHAR(255) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    checksum      VARCHAR(128),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,

    CONSTRAINT fk_files_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT fk_files_folder FOREIGN KEY (folder_id)
        REFERENCES folders (id) ON DELETE CASCADE,

    CONSTRAINT ck_files_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_files_owner ON files (owner_id);
CREATE INDEX idx_files_folder ON files (folder_id);
CREATE INDEX idx_files_owner_folder_name ON files (owner_id, folder_id, name);

-- Unique file name per owner within the same folder scope.
-- NULL folder (root files) is folded to a fixed UUID because Postgres
-- treats NULLs as distinct in unique constraints, which would otherwise
-- allow duplicate root file names.
CREATE UNIQUE INDEX uq_files_owner_folder_name
    ON files (
        owner_id,
        COALESCE(folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
        name
    );
