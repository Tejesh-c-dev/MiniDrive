ALTER TABLE object_replicas DROP CONSTRAINT ck_object_replicas_status;
ALTER TABLE object_replicas
    ADD CONSTRAINT ck_object_replicas_status
    CHECK (status IN ('PENDING', 'HEALTHY', 'FAILED', 'DELETING'));
