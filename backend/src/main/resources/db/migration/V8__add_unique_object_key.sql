-- Phase 4.2: each file metadata record points at exactly one object in Silo,
-- so the object key must be unique across the whole files table.
-- V7__create_files_metadata.sql is already applied and must not be modified.

ALTER TABLE files
    ADD CONSTRAINT uq_files_object_key UNIQUE (object_key);
