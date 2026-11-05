package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.audit.AuditLogFilterRequest;
import site.siyoung.beforeaibe.dto.audit.AuditLogResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.CreatedAtCursor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 감사 로그 검색·상세 (3.9, ADM-07·DEP-05). 역할별 범위를 자동 적용함
// ADMIN: 전체 / DEPT_MANAGER: 로그의 부서(audit_logs.department_id)가 소속 부서 / EMPLOYEE: 본인이 요청한 요청의 로그
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogService {

    // 시각 조건이 없을 때 쓰는 경계값. 시각 파라미터를 null로 바인딩할 수 없어 항상 값을 채움
    private static final Instant MIN_TIME = Instant.EPOCH;
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59Z");

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public CursorPageResponse<AuditLogResponse> list(UUID userId, AuditLogFilterRequest filters,
                                                     String cursor, int limit) {
        Scope scope = resolveScope(findUser(userId));
        validatePeriod(filters);

        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<AuditLog> logs;
        if (cursor == null || cursor.isBlank()) {
            logs = auditLogRepository.findPage(scope.departmentId(), scope.requesterId(),
                    filters.actorId(), filters.departmentId(), filters.projectId(), filters.requestId(),
                    filters.action(), filters.network(), from(filters), to(filters), fetchLimit);
        } else {
            CreatedAtCursor decoded = CreatedAtCursor.decode(cursor);
            logs = auditLogRepository.findPageAfter(scope.departmentId(), scope.requesterId(),
                    filters.actorId(), filters.departmentId(), filters.projectId(), filters.requestId(),
                    filters.action(), filters.network(), from(filters), to(filters),
                    decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = logs.size() > limit;
        List<AuditLog> page = hasNext ? logs.subList(0, limit) : logs;
        String nextCursor = hasNext
                ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new CursorPageResponse<>(page.stream().map(AuditLogResponse::from).toList(), nextCursor);
    }

    public AuditLogResponse get(UUID userId, UUID logId) {
        Scope scope = resolveScope(findUser(userId));
        AuditLog log = auditLogRepository.findById(logId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "감사 로그를 찾을 수 없습니다"));
        if (!scope.contains(log)) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return AuditLogResponse.from(log);
    }

    // 내보내기(AuditExportService)에서도 같은 범위 규칙을 씀
    public Scope resolveScope(User user) {
        return switch (user.getRole()) {
            case ADMIN -> new Scope(null, null);
            case DEPT_MANAGER -> {
                // 부서가 없는 부서 관리자에게 범위를 비워 두면 전체가 보이므로 막음
                if (user.getDepartmentId() == null) {
                    throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "소속 부서가 없습니다");
                }
                yield new Scope(user.getDepartmentId(), null);
            }
            case EMPLOYEE -> new Scope(null, user.getId());
        };
    }

    // from이 to보다 늦으면 검색할 수 없음
    public void validatePeriod(AuditLogFilterRequest filters) {
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다");
        }
    }

    public long count(Scope scope, AuditLogFilterRequest filters) {
        return auditLogRepository.countByConditions(scope.departmentId(), scope.requesterId(),
                filters.actorId(), filters.departmentId(), filters.projectId(), filters.requestId(),
                filters.action(), filters.network(), from(filters), to(filters));
    }

    public List<AuditLog> findAllForExport(Scope scope, AuditLogFilterRequest filters) {
        return auditLogRepository.findAllForExport(scope.departmentId(), scope.requesterId(),
                filters.actorId(), filters.departmentId(), filters.projectId(), filters.requestId(),
                filters.action(), filters.network(), from(filters), to(filters));
    }

    private Instant from(AuditLogFilterRequest filters) {
        return filters.from() == null ? MIN_TIME : filters.from();
    }

    private Instant to(AuditLogFilterRequest filters) {
        return filters.to() == null ? MAX_TIME : filters.to();
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    // 역할별 조회 범위. departmentId·requesterId가 모두 null이면 전체
    public record Scope(UUID departmentId, UUID requesterId) {

        boolean contains(AuditLog log) {
            if (departmentId != null
                    && (log.getDepartment() == null || !departmentId.equals(log.getDepartment().getId()))) {
                return false;
            }
            return requesterId == null
                    || (log.getRequest() != null && requesterId.equals(log.getRequest().getRequesterId()));
        }
    }
}
