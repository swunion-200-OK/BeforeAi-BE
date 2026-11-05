package site.siyoung.beforeaibe.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.audit.AuditLogFilterRequest;
import site.siyoung.beforeaibe.dto.audit.AuditLogResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.service.AuditLogService;

import java.time.Instant;
import java.util.UUID;

// 감사 로그 검색·상세 (API 명세서 3.9). 망 전체에서 사용 가능하고 역할별 범위는 서비스에서 적용
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100). from·to는 ISO 8601 시각
    @GetMapping
    public CursorPageResponse<AuditLogResponse> auditLogs(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) @Size(max = 50) String action,
            @RequestParam(required = false) Network network,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        AuditLogFilterRequest filters = new AuditLogFilterRequest(actorId, departmentId, projectId, requestId,
                action, network, from, to);
        return auditLogService.list(authUser.id(), filters, cursor, limit);
    }

    @GetMapping("/{logId}")
    public AuditLogResponse auditLog(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID logId) {
        return auditLogService.get(authUser.id(), logId);
    }
}
