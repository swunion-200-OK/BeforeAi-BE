package site.siyoung.beforeaibe.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.siyoung.beforeaibe.dto.audit.AuditExportCreateRequest;
import site.siyoung.beforeaibe.dto.audit.AuditExportFile;
import site.siyoung.beforeaibe.dto.audit.AuditExportResponse;
import site.siyoung.beforeaibe.dto.audit.AuditLogFilterRequest;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.AuditExportRepository;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.CreatedAtCursor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 감사 로그 내보내기 (3.9, CORE-13). 내부망 전용(컨트롤러에서 보장)이며 요청한 본인만 조회·다운로드함
// 시작 때 건수를 세어 10만 건 제한을 확인하고, 파일은 커밋 뒤 백그라운드에서 만듦.
// 만료(준비 후 1시간)는 조회·다운로드 때 확인해 EXPIRED로 바꾸고 파일을 지움
@Slf4j
@Service
@Transactional(readOnly = true)
public class AuditExportService {

    static final String AUDIT_EXPORTED = "AUDIT_EXPORTED";

    private final AuditExportRepository auditExportRepository;
    private final AuditLogRepository auditLogRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AuditExportFileService auditExportFileService;
    private final TaskExecutor taskExecutor;
    private final long maxRows;

    public AuditExportService(AuditExportRepository auditExportRepository,
                              AuditLogRepository auditLogRepository,
                              DepartmentRepository departmentRepository,
                              UserRepository userRepository,
                              AuditLogService auditLogService,
                              AuditExportFileService auditExportFileService,
                              @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                              @Value("${audit.export.max-rows}") long maxRows) {
        this.auditExportRepository = auditExportRepository;
        this.auditLogRepository = auditLogRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.auditExportFileService = auditExportFileService;
        this.taskExecutor = taskExecutor;
        this.maxRows = maxRows;
    }

    @Transactional
    public AuditExportResponse start(UUID userId, AuditExportCreateRequest request, Network network, String ip) {
        User user = findUser(userId);
        AuditLogService.Scope scope = auditLogService.resolveScope(user);
        AuditLogFilterRequest filters = narrow(scope,
                request.filters() == null ? AuditLogFilterRequest.empty() : request.filters());
        auditLogService.validatePeriod(filters);

        long rowCount = auditLogService.count(scope, filters);
        if (rowCount > maxRows) {
            throw new ApiException(ErrorCode.EXPORT_TOO_LARGE);
        }

        AuditExport export = auditExportRepository.save(AuditExport.builder()
                .requester(user)
                .format(request.format())
                .status(ExportStatus.PROCESSING)
                .filters(filters.toExportFilters())
                .rowCount((int) rowCount)
                .build());
        // 조건·건수를 감사 기록으로 남김. 부서 범위 조회에 걸리도록 요청자의 부서를 대상으로 둠
        auditLogRepository.save(AuditLog.builder()
                .actor(user)
                .action(AUDIT_EXPORTED)
                .department(user.getDepartmentId() == null
                        ? null : departmentRepository.getReferenceById(user.getDepartmentId()))
                .taskIds(List.of())
                .network(network)
                .ip(ip)
                .detail(Map.of(
                        "exportId", export.getId().toString(),
                        "format", request.format().name(),
                        "rowCount", rowCount,
                        "filters", filters.toExportFilters()))
                .build());

        generateAfterCommit(export.getId(), scope, filters);
        return AuditExportResponse.from(export);
    }

    @Transactional
    public CursorPageResponse<AuditExportResponse> list(UUID userId, String cursor, int limit) {
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<AuditExport> exports;
        if (cursor == null || cursor.isBlank()) {
            exports = auditExportRepository.findFirstPage(userId, fetchLimit);
        } else {
            CreatedAtCursor decoded = CreatedAtCursor.decode(cursor);
            exports = auditExportRepository.findPageAfter(userId, decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = exports.size() > limit;
        List<AuditExport> page = hasNext ? exports.subList(0, limit) : exports;
        Instant now = Instant.now();
        page.forEach(export -> expireIfNeeded(export, now));
        String nextCursor = hasNext
                ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new CursorPageResponse<>(page.stream().map(AuditExportResponse::from).toList(), nextCursor);
    }

    @Transactional
    public AuditExportResponse get(UUID userId, UUID exportId) {
        AuditExport export = findOwnExport(userId, exportId);
        expireIfNeeded(export, Instant.now());
        return AuditExportResponse.from(export);
    }

    // READY가 아니면(준비 중·실패·만료) 내려받을 수 없음
    @Transactional
    public AuditExportFile download(UUID userId, UUID exportId) {
        AuditExport export = findOwnExport(userId, exportId);
        expireIfNeeded(export, Instant.now());
        if (export.getStatus() != ExportStatus.READY) {
            throw new ApiException(ErrorCode.INVALID_STATE, "내려받을 수 있는 상태가 아닙니다");
        }
        return AuditExportFile.from(export);
    }

    // 역할별 범위를 넘는 조건은 좁힘: 부서 관리자가 부서를 정하지 않으면 소속 부서로 채움.
    // 끝 시각이 없으면 시작 시각으로 고정해, 파일을 만드는 동안 쌓인 로그로 건수(rowCount)가 달라지지 않게 함
    private AuditLogFilterRequest narrow(AuditLogService.Scope scope, AuditLogFilterRequest filters) {
        UUID departmentId = filters.departmentId() == null ? scope.departmentId() : filters.departmentId();
        Instant to = filters.to() == null ? Instant.now() : filters.to();
        return new AuditLogFilterRequest(filters.actorId(), departmentId, filters.projectId(), filters.requestId(),
                filters.action(), filters.network(), filters.from(), to);
    }

    // 내보내기 행이 커밋된 뒤에 백그라운드 스레드에서 파일을 만듦 (커밋 전이면 다른 트랜잭션에서 행이 보이지 않음)
    private void generateAfterCommit(UUID exportId, AuditLogService.Scope scope, AuditLogFilterRequest filters) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.execute(() -> generate(exportId, scope, filters));
            }
        });
    }

    private void generate(UUID exportId, AuditLogService.Scope scope, AuditLogFilterRequest filters) {
        try {
            auditExportFileService.generate(exportId, scope, filters);
        } catch (RuntimeException e) {
            log.error("감사 로그 내보내기 파일 생성 실패: exportId={}", exportId, e);
            markFailed(exportId);
        }
    }

    // 실패 표시마저 실패하면(DB 연결 끊김 등) 실행기 스레드로 예외를 넘기지 않고 로그만 남김. 이 경우 PROCESSING에 남음
    private void markFailed(UUID exportId) {
        try {
            auditExportFileService.markFailed(exportId);
        } catch (RuntimeException e) {
            log.error("감사 로그 내보내기 실패 상태 저장 실패: exportId={}", exportId, e);
        }
    }

    private void expireIfNeeded(AuditExport export, Instant now) {
        if (export.isExpiredAt(now)) {
            export.expire();
        }
    }

    private AuditExport findOwnExport(UUID userId, UUID exportId) {
        AuditExport export = auditExportRepository.findById(exportId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "내보내기를 찾을 수 없습니다"));
        if (!export.getRequester().getId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "요청한 본인만 볼 수 있습니다");
        }
        return export;
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }
}
