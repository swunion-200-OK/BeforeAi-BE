package site.siyoung.beforeaibe.dto.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;

// 감사 로그 내보내기 시작 (3.9 POST /audit-logs/exports). filters를 생략하면 조건 없이(역할별 범위만) 내보냄
public record AuditExportCreateRequest(
        @NotNull ExportFormat format,
        @Valid AuditLogFilterRequest filters
) {
}
