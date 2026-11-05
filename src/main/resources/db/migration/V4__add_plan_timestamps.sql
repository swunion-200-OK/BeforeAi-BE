-- 진행 상황 트리에서 Plan 순서(Plan v1, v2 …)를 정하기 위해 생성 시각을 둠.
-- Plan 해시를 제출 때 채우므로 수정 가능한 엔티티(BaseTimeEntity)와 같이 수정 시각도 둠. 기존 행은 마이그레이션 시각으로 채움
ALTER TABLE plans
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE plans
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- 요청별 Plan을 생성 순서대로 조회 (GET /requests/{requestId}/progress)
CREATE INDEX idx_plans_request_id_created_at ON plans (request_id, created_at);
