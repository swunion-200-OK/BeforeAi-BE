ALTER TABLE policy_versions ADD COLUMN policy_payload jsonb;

CREATE TABLE policy_change_lock (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO policy_change_lock (id) VALUES (1);

CREATE INDEX idx_forbidden_proposals_created_id ON forbidden_proposals (created_at DESC, id DESC);
CREATE INDEX idx_forbidden_proposals_status_created_id ON forbidden_proposals (status, created_at DESC, id DESC);
CREATE INDEX idx_forbidden_rules_department_created_id ON forbidden_rules (department_id, created_at DESC, id DESC);
