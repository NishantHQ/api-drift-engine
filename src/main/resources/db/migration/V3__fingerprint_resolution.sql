-- Manual Resolution support for Change Fingerprints
-- Allows teams to acknowledge and resolve breaking changes with audit trail.
ALTER TABLE change_fingerprints
    ADD COLUMN resolved_by VARCHAR(200),
    ADD COLUMN resolution_notes TEXT,
    ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
