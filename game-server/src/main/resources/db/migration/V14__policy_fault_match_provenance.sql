ALTER TABLE match_results
    ADD COLUMN policy_fault_incident_id VARCHAR(64),
    ADD COLUMN policy_fault_code VARCHAR(96),
    ADD COLUMN strategy_evidence_eligible BOOLEAN NOT NULL DEFAULT TRUE;
