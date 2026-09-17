-- MySQL manual migration for installations with ddl-auto=validate/none.
-- Run ONCE after backup and BEFORE starting the new application.
-- Do not run after Hibernate ddl-auto=update has already created these objects.
ALTER TABLE stored_files
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at DATETIME(6) NULL;
CREATE TABLE file_revisions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    file_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    stored_name VARCHAR(36) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    size BIGINT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_file_revision_version UNIQUE(file_id, version),
    CONSTRAINT fk_file_revision_file FOREIGN KEY(file_id) REFERENCES stored_files(id)
);
