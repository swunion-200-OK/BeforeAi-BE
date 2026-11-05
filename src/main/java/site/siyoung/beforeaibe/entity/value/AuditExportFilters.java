package site.siyoung.beforeaibe.entity.value;

import com.fasterxml.jackson.annotation.JsonInclude;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.UUID;

// 감사 로그 내보내기 조건. GET /audit-logs 검색 조건과 같고, 역할별 범위로 좁힌 값을 저장함
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditExportFilters(
        UUID actorId,
        UUID departmentId,
        UUID projectId,
        UUID requestId,
        String action,
        Network network,
        Instant from,
        Instant to
) {
}
