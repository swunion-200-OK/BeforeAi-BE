-- 재분석 입력 보존, 웹쉘 검증 실패 항목
ALTER TABLE requests
    ADD COLUMN reanalysis_input text,
    ADD COLUMN failed_checks    jsonb;

-- 누가 언제 실행을 중단했는지
ALTER TABLE executions
    ADD COLUMN killed_by uuid REFERENCES users (id),
    ADD COLUMN killed_at timestamptz;

CREATE INDEX idx_executions_killed_by ON executions (killed_by);

-- 요청 상태 변경 이력. 시스템(AI 분석, 웹쉘 콜백)이 바꾸면 actor_id는 null
CREATE TABLE request_status_events
(
    id          uuid PRIMARY KEY,
    request_id  uuid        NOT NULL REFERENCES requests (id) ON DELETE CASCADE,
    status      varchar(30) NOT NULL,
    actor_id    uuid REFERENCES users (id) ON DELETE SET NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX idx_request_status_events_request_id ON request_status_events (request_id, occurred_at);
CREATE INDEX idx_request_status_events_actor_id ON request_status_events (actor_id);

-- 승인자의 Plan 수정 요청
CREATE TABLE approval_change_requests
(
    id           uuid PRIMARY KEY,
    request_id   uuid        NOT NULL REFERENCES requests (id),
    plan_id      uuid        NOT NULL REFERENCES plans (id),
    requested_by uuid        NOT NULL REFERENCES users (id),
    task_ids     jsonb       NOT NULL,
    reason       text        NOT NULL,
    network      varchar(10) NOT NULL,
    created_at   timestamptz NOT NULL
);

CREATE INDEX idx_approval_change_requests_request_id ON approval_change_requests (request_id, created_at);
CREATE INDEX idx_approval_change_requests_plan_id ON approval_change_requests (plan_id);
CREATE INDEX idx_approval_change_requests_requested_by ON approval_change_requests (requested_by);
