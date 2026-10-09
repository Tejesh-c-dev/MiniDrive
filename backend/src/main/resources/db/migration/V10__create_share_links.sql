-- Phase 6.3: secure, revocable, optionally expiring public share links.
--
-- A share link is an anonymous bearer token scoped to exactly one file. The
-- raw token is never stored: only its SHA-256 hash is persisted, so a database
-- leak does not expose usable links. The hash is unique so the same token can
-- never resolve to two rows. Ownership is not duplicated here -- the link
-- simply points at a files row and inherits the owner's file through it.
--
-- Revocation deletes the row (like Phase 6.1 permission revocation), which
-- immediately makes the token unusable. Expiration (expires_at) is enforced
-- by the server on every public request, not merely hidden in the UI.

CREATE TABLE share_links (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id     UUID NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP,

    CONSTRAINT fk_share_links_file FOREIGN KEY (file_id)
        REFERENCES files (id) ON DELETE CASCADE,

    CONSTRAINT uq_share_links_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_share_links_file ON share_links (file_id);
