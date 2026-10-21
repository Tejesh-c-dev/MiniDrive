CREATE TABLE object_locations (
    object_key VARCHAR(512) PRIMARY KEY,
    storage_node_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Version rows represent durable content, while files without versions may be
-- metadata-only placeholders. Resumable chunk rows point at staged bytes.
INSERT INTO object_locations (object_key, storage_node_id)
SELECT object_key, 'primary' FROM file_versions
ON CONFLICT (object_key) DO NOTHING;

INSERT INTO object_locations (object_key, storage_node_id)
SELECT staging_object_key, 'primary' FROM upload_chunks
ON CONFLICT (object_key) DO NOTHING;
