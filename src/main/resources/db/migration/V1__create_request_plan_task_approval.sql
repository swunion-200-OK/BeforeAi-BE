CREATE TABLE requests
(
    id                uuid PRIMARY KEY,
    project_id        uuid        NOT NULL,
    requester_id      uuid        NOT NULL,
    prompt            text        NOT NULL,
    source            varchar(20) NOT NULL,
    status            varchar(30) NOT NULL,
    analysis_attempts integer     NOT NULL DEFAULT 0,
    current_plan_id   uuid,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE TABLE plans
(
    id                 uuid PRIMARY KEY,
    request_id         uuid         NOT NULL REFERENCES requests (id),
    kind               varchar(20)  NOT NULL,
    repository         varchar(512) NOT NULL,
    base_commit        varchar(64)  NOT NULL,
    plan_hash          varchar(128),
    executor_signature text
);

CREATE INDEX idx_plans_request_id ON plans (request_id);

ALTER TABLE requests
    ADD CONSTRAINT fk_requests_current_plan FOREIGN KEY (current_plan_id) REFERENCES plans (id);

CREATE TABLE tasks
(
    id                  uuid PRIMARY KEY,
    plan_id             uuid          NOT NULL REFERENCES plans (id),
    task_order          integer       NOT NULL,
    type                varchar(20)   NOT NULL,
    risk                varchar(20)   NOT NULL,
    title               varchar(255)  NOT NULL,
    target              varchar(1024) NOT NULL,
    content             jsonb         NOT NULL,
    related_departments jsonb         NOT NULL,
    required_approvers  jsonb         NOT NULL,
    status              varchar(20)   NOT NULL,
    CONSTRAINT uk_tasks_plan_order UNIQUE (plan_id, task_order)
);

CREATE TABLE approvals
(
    id                        uuid PRIMARY KEY,
    request_id                uuid         NOT NULL REFERENCES requests (id),
    plan_id                   uuid         NOT NULL REFERENCES plans (id),
    approver_id               uuid         NOT NULL,
    key_id                    uuid         NOT NULL,
    plan_hash                 varchar(128) NOT NULL,
    task_ids                  jsonb        NOT NULL,
    network                   varchar(10)  NOT NULL,
    envelope_alg              varchar(50)  NOT NULL,
    envelope_encapsulated_key text         NOT NULL,
    envelope_ciphertext       text         NOT NULL,
    created_at                timestamptz  NOT NULL
);

CREATE INDEX idx_approvals_request_id ON approvals (request_id);
CREATE INDEX idx_approvals_plan_id ON approvals (plan_id);
