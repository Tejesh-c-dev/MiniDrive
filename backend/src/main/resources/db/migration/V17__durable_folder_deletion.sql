ALTER TABLE folders ADD COLUMN deleting BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE folder_deletion_jobs (
    id UUID PRIMARY KEY,
    root_folder_id UUID UNIQUE,
    owner_id UUID,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_folder_deletion_jobs_folder FOREIGN KEY (root_folder_id) REFERENCES folders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_folder_deletion_jobs_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_folder_deletion_jobs_status CHECK (status IN ('PENDING', 'RETRY')),
    CONSTRAINT ck_folder_deletion_jobs_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_folder_deletion_jobs_scope CHECK ((root_folder_id IS NULL) = (owner_id IS NULL))
);
CREATE INDEX idx_folder_deletion_jobs_retry ON folder_deletion_jobs(status, next_attempt_at, created_at);

CREATE TABLE folder_deletion_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_folder_deletion_objects_job FOREIGN KEY (job_id) REFERENCES folder_deletion_jobs(id) ON DELETE CASCADE,
    CONSTRAINT uq_folder_deletion_objects_job_key UNIQUE(job_id, object_key)
);
CREATE INDEX idx_folder_deletion_objects_pending ON folder_deletion_objects(job_id, deleted);
