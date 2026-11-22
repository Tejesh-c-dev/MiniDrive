-- Idempotently provision a login used only by postgres_exporter.
-- The script runner supplies exporter_password as a psql variable.
SELECT format('CREATE ROLE minidrive_exporter LOGIN PASSWORD %L', :'exporter_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'minidrive_exporter')
\gexec

ALTER ROLE minidrive_exporter LOGIN PASSWORD :'exporter_password';
SELECT format('GRANT CONNECT ON DATABASE %I TO minidrive_exporter', current_database())
\gexec
GRANT pg_monitor TO minidrive_exporter;
