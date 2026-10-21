-- Persistent health and integrity state for each expected object copy.
CREATE TABLE object_replicas (
    object_key       VARCHAR(512) NOT NULL,
    storage_node_id  VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    size_bytes       BIGINT NOT NULL,
    checksum         VARCHAR(64) NOT NULL,
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),
    next_attempt_at  TIMESTAMP,
    verified_at      TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_object_replicas PRIMARY KEY (object_key, storage_node_id),
    CONSTRAINT ck_object_replicas_status CHECK (status IN ('PENDING', 'HEALTHY', 'FAILED')),
    CONSTRAINT ck_object_replicas_size CHECK (size_bytes >= 0)
);

CREATE INDEX idx_object_replicas_repair
    ON object_replicas (status, next_attempt_at);
