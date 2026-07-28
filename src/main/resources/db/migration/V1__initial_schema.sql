-- Vendor Registration & Monitoring Configuration
CREATE TABLE vendor_configs (
    id BIGSERIAL PRIMARY KEY,
    vendor_name VARCHAR(100) NOT NULL UNIQUE,
    spec_url VARCHAR(2048) NOT NULL,
    cron_expression VARCHAR(50) NOT NULL DEFAULT '0 0 * * * *',
    auth_header_name VARCHAR(100),
    encrypted_auth_token TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Historic OpenAPI Specification Snapshots
CREATE TABLE spec_snapshots (
    id BIGSERIAL PRIMARY KEY,
    vendor_id BIGINT NOT NULL REFERENCES vendor_configs(id) ON DELETE CASCADE,
    content_hash VARCHAR(64) NOT NULL,
    spec_version VARCHAR(20),
    raw_spec JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_spec_snapshots_vendor_hash ON spec_snapshots(vendor_id, content_hash);

-- Audit Runs
CREATE TABLE diff_audit_runs (
    id BIGSERIAL PRIMARY KEY,
    vendor_id BIGINT NOT NULL REFERENCES vendor_configs(id) ON DELETE CASCADE,
    old_snapshot_id BIGINT REFERENCES spec_snapshots(id),
    new_snapshot_id BIGINT REFERENCES spec_snapshots(id),
    total_changes INT NOT NULL DEFAULT 0,
    breaking_changes INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'SUCCESS',
    executed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Detected Change Items & Deduplication Fingerprints
CREATE TABLE change_fingerprints (
    id BIGSERIAL PRIMARY KEY,
    audit_run_id BIGINT NOT NULL REFERENCES diff_audit_runs(id) ON DELETE CASCADE,
    vendor_id BIGINT NOT NULL REFERENCES vendor_configs(id) ON DELETE CASCADE,
    fingerprint_hash VARCHAR(64) NOT NULL,
    change_type VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL, -- CRITICAL, HIGH, MEDIUM, LOW, INFO
    http_method VARCHAR(10),
    endpoint_path VARCHAR(500),
    json_pointer VARCHAR(500),
    description TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_fingerprint_active ON change_fingerprints(vendor_id, fingerprint_hash) WHERE is_active = TRUE;

-- Quartz Scheduler Tables (auto-managed by Spring Boot Quartz)
