-- Phase 3: user-owned folders with optional nested parents.

CREATE TABLE folders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID NOT NULL,
    parent_folder_id  UUID,
    name              VARCHAR(255) NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,

    CONSTRAINT fk_folders_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT fk_folders_parent FOREIGN KEY (parent_folder_id)
        REFERENCES folders (id) ON DELETE CASCADE
);

CREATE INDEX idx_folders_owner ON folders (owner_id);
CREATE INDEX idx_folders_parent ON folders (parent_folder_id);

-- Unique folder name per owner within the same parent scope.
-- NULL parent (root folders) is folded to a fixed UUID because Postgres
-- treats NULLs as distinct in unique constraints, which would otherwise
-- allow duplicate root folder names.
CREATE UNIQUE INDEX uq_folders_owner_parent_name
    ON folders (
        owner_id,
        COALESCE(parent_folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
        name
    );
