-- 감사 로그 내보내기 파일(CSV·XLSX)을 DB에 보관. 준비 후 1시간이 지나 만료되면 비움
ALTER TABLE audit_exports
    ADD COLUMN file_content bytea;
