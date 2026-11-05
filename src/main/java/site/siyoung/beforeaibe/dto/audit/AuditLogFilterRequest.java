package site.siyoung.beforeaibe.dto.audit;

import jakarta.validation.constraints.Size;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.value.AuditExportFilters;

import java.time.Instant;
import java.util.UUID;

// 감사 로그 검색 조건 (GET /audit-logs의 쿼리, POST /audit-logs/exports의 filters). 모든 항목은 선택
// from은 포함, to는 제외(from <= 시각 < to)로 검색함
public record AuditLogFilterRequest(
        UUID actorId,
        UUID departmentId,
        UUID projectId,
        UUID requestId,
        @Size(max = 50) String action,
        Network network,
        Instant from,
        Instant to
) {

    public static AuditLogFilterRequest empty() {
        return new AuditLogFilterRequest(null, null, null, null, null, null, null, null);
    }

    public static AuditLogFilterRequest from(AuditExportFilters filters) {
        return new AuditLogFilterRequest(filters.actorId(), filters.departmentId(), filters.projectId(),
                filters.requestId(), filters.action(), filters.network(), filters.from(), filters.to());
    }

    public AuditExportFilters toExportFilters() {
        return new AuditExportFilters(actorId, departmentId, projectId, requestId, action, network, from, to);
    }
}
