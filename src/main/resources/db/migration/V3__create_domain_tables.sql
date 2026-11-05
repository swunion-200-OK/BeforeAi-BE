-- 조직·프로젝트·기획서
CREATE TABLE departments
(
    id         uuid PRIMARY KEY,
    name       varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE TABLE projects
(
    id            uuid PRIMARY KEY,
    name          varchar(100) NOT NULL,
    department_id uuid         NOT NULL REFERENCES departments (id),
    repository    varchar(512) NOT NULL,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);

CREATE INDEX idx_projects_department_id ON projects (department_id);

CREATE TABLE project_owners
(
    id         uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects (id),
    user_id    uuid NOT NULL REFERENCES users (id),
    CONSTRAINT uk_project_owners_project_user UNIQUE (project_id, user_id)
);

-- 담당자로 지정된 프로젝트 조회(승인 후보 계산)
CREATE INDEX idx_project_owners_user_id ON project_owners (user_id);

CREATE TABLE project_spec_versions
(
    id                    uuid PRIMARY KEY,
    project_id            uuid        NOT NULL REFERENCES projects (id),
    version               integer     NOT NULL,
    body                  text        NOT NULL,
    reason                text,
    restored_from_version integer,
    created_by            uuid        NOT NULL REFERENCES users (id),
    created_at            timestamptz NOT NULL,
    CONSTRAINT uk_project_spec_versions_project_version UNIQUE (project_id, version)
);

CREATE TABLE spec_change_requests
(
    id               uuid PRIMARY KEY,
    project_id       uuid        NOT NULL REFERENCES projects (id),
    requester_id     uuid        NOT NULL REFERENCES users (id),
    content          text        NOT NULL,
    reason           text        NOT NULL,
    status           varchar(20) NOT NULL,
    rejection_reason text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

-- 프로젝트별 상태 필터 목록 (?status=OPEN)
CREATE INDEX idx_spec_change_requests_project_id_status ON spec_change_requests (project_id, status);
CREATE INDEX idx_spec_change_requests_requester_id ON spec_change_requests (requester_id);

-- 금지 작업
CREATE TABLE forbidden_proposals
(
    id               uuid PRIMARY KEY,
    department_id    uuid          NOT NULL REFERENCES departments (id),
    task_type        varchar(20)   NOT NULL,
    target           varchar(1024) NOT NULL,
    reason           text          NOT NULL,
    status           varchar(20)   NOT NULL,
    rejection_reason text,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL
);

CREATE INDEX idx_forbidden_proposals_department_id ON forbidden_proposals (department_id);
CREATE INDEX idx_forbidden_proposals_status ON forbidden_proposals (status);

CREATE TABLE forbidden_rules
(
    id             uuid PRIMARY KEY,
    department_id  uuid          NOT NULL REFERENCES departments (id),
    task_type      varchar(20)   NOT NULL,
    target         varchar(1024) NOT NULL,
    reason         text          NOT NULL,
    status         varchar(20)   NOT NULL,
    release_reason text,
    created_at     timestamptz   NOT NULL,
    updated_at     timestamptz   NOT NULL
);

CREATE INDEX idx_forbidden_rules_department_id ON forbidden_rules (department_id);

-- 정책
CREATE TABLE policy_versions
(
    id               uuid PRIMARY KEY,
    version          integer      NOT NULL,
    preset           varchar(10)  NOT NULL,
    approval_matrix  jsonb        NOT NULL,
    policy_hash      varchar(128) NOT NULL,
    admin_key_id     uuid         NOT NULL,
    admin_signature  text         NOT NULL,
    webshell_applied boolean      NOT NULL,
    created_by       uuid         NOT NULL REFERENCES users (id),
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT uk_policy_versions_version UNIQUE (version)
);

-- 실행
CREATE TABLE executions
(
    id                    uuid PRIMARY KEY,
    request_id            uuid         NOT NULL REFERENCES requests (id),
    plan_id               uuid         NOT NULL REFERENCES plans (id),
    webshell_execution_id uuid         NOT NULL,
    plan_hash             varchar(128) NOT NULL,
    status                varchar(30)  NOT NULL,
    webshell_signature    text         NOT NULL,
    kill_reason           text,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    CONSTRAINT uk_executions_webshell_execution_id UNIQUE (webshell_execution_id)
);

CREATE INDEX idx_executions_request_id ON executions (request_id);
CREATE INDEX idx_executions_plan_id ON executions (plan_id);
-- 전사 일괄 중단 때 실행 중인 세션 조회
CREATE INDEX idx_executions_status ON executions (status);

CREATE TABLE execution_events
(
    id                 uuid PRIMARY KEY,
    event_id           uuid        NOT NULL,
    execution_id       uuid        NOT NULL REFERENCES executions (id),
    type               varchar(50) NOT NULL,
    task_id            uuid REFERENCES tasks (id),
    occurred_at        timestamptz NOT NULL,
    data               jsonb       NOT NULL,
    webshell_signature text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT uk_execution_events_event_id UNIQUE (event_id)
);

CREATE INDEX idx_execution_events_execution_id ON execution_events (execution_id);
CREATE INDEX idx_execution_events_task_id ON execution_events (task_id);

CREATE TABLE execution_logs
(
    id                 uuid PRIMARY KEY,
    execution_id       uuid        NOT NULL REFERENCES executions (id),
    request_id         uuid        NOT NULL REFERENCES requests (id),
    task_id            uuid REFERENCES tasks (id),
    log_sequence       bigint      NOT NULL,
    logged_at          timestamptz NOT NULL,
    message            text        NOT NULL,
    webshell_signature text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT uk_execution_logs_execution_sequence UNIQUE (execution_id, log_sequence)
);

-- 요청 단위 로그 페이지·스트림 조회
CREATE INDEX idx_execution_logs_request_id_sequence ON execution_logs (request_id, log_sequence);
CREATE INDEX idx_execution_logs_task_id ON execution_logs (task_id);

-- 승인 거부
CREATE TABLE rejections
(
    id          uuid PRIMARY KEY,
    request_id  uuid        NOT NULL REFERENCES requests (id),
    plan_id     uuid        NOT NULL REFERENCES plans (id),
    rejecter_id uuid        NOT NULL REFERENCES users (id),
    task_ids    jsonb       NOT NULL,
    reason      text        NOT NULL,
    network     varchar(10) NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE INDEX idx_rejections_request_id ON rejections (request_id);
CREATE INDEX idx_rejections_plan_id ON rejections (plan_id);
CREATE INDEX idx_rejections_rejecter_id ON rejections (rejecter_id);

-- 문서
CREATE TABLE documents
(
    id              uuid PRIMARY KEY,
    project_id      uuid          NOT NULL REFERENCES projects (id),
    title           varchar(255)  NOT NULL,
    path            varchar(1024) NOT NULL,
    current_version integer       NOT NULL,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    CONSTRAINT uk_documents_project_path UNIQUE (project_id, path)
);

CREATE TABLE document_versions
(
    id          uuid PRIMARY KEY,
    document_id uuid        NOT NULL REFERENCES documents (id),
    version     integer     NOT NULL,
    body        text        NOT NULL,
    request_id  uuid        NOT NULL REFERENCES requests (id),
    created_at  timestamptz NOT NULL,
    CONSTRAINT uk_document_versions_document_version UNIQUE (document_id, version)
);

CREATE INDEX idx_document_versions_request_id ON document_versions (request_id);

-- 감사 로그
CREATE TABLE audit_logs
(
    id            uuid PRIMARY KEY,
    actor_id      uuid REFERENCES users (id),
    action        varchar(50) NOT NULL,
    department_id uuid REFERENCES departments (id),
    project_id    uuid REFERENCES projects (id),
    request_id    uuid REFERENCES requests (id),
    task_ids      jsonb       NOT NULL,
    network       varchar(10),
    ip            varchar(45),
    detail        jsonb       NOT NULL,
    created_at    timestamptz NOT NULL
);

-- 기간 검색과 역할별 범위(행위자·부서·프로젝트·요청) 필터
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at DESC, id DESC);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs (actor_id);
CREATE INDEX idx_audit_logs_department_id ON audit_logs (department_id);
CREATE INDEX idx_audit_logs_project_id ON audit_logs (project_id);
CREATE INDEX idx_audit_logs_request_id ON audit_logs (request_id);

CREATE TABLE audit_exports
(
    id           uuid PRIMARY KEY,
    requester_id uuid        NOT NULL REFERENCES users (id),
    format       varchar(10) NOT NULL,
    status       varchar(20) NOT NULL,
    filters      jsonb       NOT NULL,
    row_count    integer     NOT NULL,
    file_name    varchar(255),
    expires_at   timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);

CREATE INDEX idx_audit_exports_requester_id ON audit_exports (requester_id);

-- 대상 테이블이 생기기 전에 UUID로만 두었던 참조에 FK를 추가 (엔티티는 기존처럼 ID 필드로 참조)
ALTER TABLE users
    ADD CONSTRAINT fk_users_department FOREIGN KEY (department_id) REFERENCES departments (id);
ALTER TABLE requests
    ADD CONSTRAINT fk_requests_project FOREIGN KEY (project_id) REFERENCES projects (id);
ALTER TABLE requests
    ADD CONSTRAINT fk_requests_requester FOREIGN KEY (requester_id) REFERENCES users (id);

-- 부서별 사용자, 프로젝트별·요청자별 요청 목록 조회 (GET /admin/users?departmentId=, GET /requests?projectId=&mine=true)
CREATE INDEX idx_users_department_id ON users (department_id);
CREATE INDEX idx_requests_project_id ON requests (project_id);
CREATE INDEX idx_requests_requester_id ON requests (requester_id);

-- 요청: AI 확인 질문, 진행 상황 트리 버전
ALTER TABLE requests
    ADD COLUMN clarification text;
ALTER TABLE requests
    ADD COLUMN progress_version bigint NOT NULL DEFAULT 0;
