-- Phase 6.1: direct, per-user, file-scoped permissions.
--
-- Ownership remains authoritative on files.owner_id, so OWNER is deliberately
-- not a grantable role: a row exists only for an EDITOR or VIEWER collaborator,
-- and the (file_id, user_id) pair is unique so re-sharing updates a role rather
-- than duplicating access. V4__create_shares.sql stays an empty placeholder for
-- the future share-link phase and is not touched here.

CREATE TABLE file_permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id     UUID NOT NULL,
    user_id     UUID NOT NULL,
    role        VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,

    CONSTRAINT fk_file_permissions_file FOREIGN KEY (file_id)
        REFERENCES files (id) ON DELETE CASCADE,

    CONSTRAINT fk_file_permissions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT uq_file_permissions_file_user UNIQUE (file_id, user_id),

    CONSTRAINT ck_file_permissions_role CHECK (role IN ('EDITOR', 'VIEWER'))
);

CREATE INDEX idx_file_permissions_file ON file_permissions (file_id);
CREATE INDEX idx_file_permissions_user ON file_permissions (user_id);
