-- 결재 문서: 프롬프트 요청 하나당 문서 하나. 판(version)은 제출된 Plan 하나에 대응하고, 이전 판은 지우지 않고 기록으로 남김
CREATE TABLE approval_documents
(
    id              uuid PRIMARY KEY,
    request_id      uuid        NOT NULL REFERENCES requests (id),
    current_version integer     NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT uk_approval_documents_request UNIQUE (request_id)
);

-- 판 하나 = 제출된 Plan 하나. unique 제약이 (document_id, version)·plan_id 조회 인덱스를 겸함
CREATE TABLE approval_document_versions
(
    id          uuid PRIMARY KEY,
    document_id uuid        NOT NULL REFERENCES approval_documents (id),
    version     integer     NOT NULL,
    plan_id     uuid        NOT NULL REFERENCES plans (id),
    created_at  timestamptz NOT NULL,
    CONSTRAINT uk_approval_document_versions_document_version UNIQUE (document_id, version),
    CONSTRAINT uk_approval_document_versions_plan UNIQUE (plan_id)
);
