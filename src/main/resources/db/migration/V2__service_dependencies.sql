-- Service Dependency Registry for Telemetry Correlation
-- Internal services register their vendor API dependencies here,
-- enabling the UsageCorrelationService to perform real severity adjustment.
CREATE TABLE service_dependencies (
    id BIGSERIAL PRIMARY KEY,
    vendor_id BIGINT NOT NULL REFERENCES vendor_configs(id) ON DELETE CASCADE,
    endpoint_path VARCHAR(500),
    http_method VARCHAR(10),
    json_pointer VARCHAR(500),
    service_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_deps_vendor ON service_dependencies(vendor_id);
CREATE INDEX idx_service_deps_service ON service_dependencies(service_name);

-- Prevent exact duplicate registrations
CREATE UNIQUE INDEX idx_service_deps_unique
    ON service_dependencies(vendor_id, service_name, endpoint_path, http_method, COALESCE(json_pointer, ''));
