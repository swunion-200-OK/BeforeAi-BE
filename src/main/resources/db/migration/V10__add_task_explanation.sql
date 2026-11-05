-- 승인자용 AI 작업 설명. 표시 전용이라 기존 행은 null로 둠
ALTER TABLE tasks ADD COLUMN explanation jsonb;
