-- 실패 작업 재시도 Plan 표시. 결재 문서의 판으로 만들지 않기 위함
ALTER TABLE plans
    ADD COLUMN retry boolean NOT NULL DEFAULT false;
