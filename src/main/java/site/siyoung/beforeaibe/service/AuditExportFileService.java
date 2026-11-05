package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.audit.AuditLogFilterRequest;
import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.AuditExportRepository;
import site.siyoung.beforeaibe.support.AuditLogCsvWriter;
import site.siyoung.beforeaibe.support.AuditLogXlsxWriter;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

// 감사 로그 내보내기 파일 생성 (CORE-13). AuditExportService가 커밋 뒤 백그라운드 스레드에서 호출함
// 실패 표시(markFailed)는 생성 트랜잭션이 롤백돼도 남도록 별도 트랜잭션으로 둠
@Service
@RequiredArgsConstructor
public class AuditExportFileService {

    // 준비된 파일은 1시간 동안만 내려받을 수 있음
    static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CELL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(KST);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(KST);
    private static final List<String> HEADER = List.of(
            "시각(KST)", "행위자", "부서", "동작", "프로젝트", "요청 ID", "작업 ID", "접속 환경", "IP", "결과", "상세");
    private static final String TASK_ID_SEPARATOR = ";";
    private static final String FAILED_ACTION_SUFFIX = "_FAILED";
    // 접미사로 드러나지 않지만 실패로 봐야 하는 웹쉘 경보
    private static final String POLICY_MISMATCH_ACTION = "POLICY_MISMATCH";

    private final AuditExportRepository auditExportRepository;
    private final AuditLogService auditLogService;
    private final AuditLogCsvWriter csvWriter;
    private final AuditLogXlsxWriter xlsxWriter;
    private final ObjectMapper objectMapper;

    @Transactional
    public void generate(UUID exportId, AuditLogService.Scope scope, AuditLogFilterRequest filters) {
        AuditExport export = findExport(exportId);
        List<List<String>> rows = auditLogService.findAllForExport(scope, filters).stream()
                .map(this::toRow)
                .toList();
        byte[] content = export.getFormat() == ExportFormat.CSV
                ? csvWriter.write(HEADER, rows)
                : xlsxWriter.write(HEADER, rows);
        export.markReady(fileName(export, filters), content, Instant.now().plus(DOWNLOAD_TTL));
    }

    @Transactional
    public void markFailed(UUID exportId) {
        findExport(exportId).markFailed();
    }

    private List<String> toRow(AuditLog log) {
        return List.of(
                CELL_TIME.format(log.getCreatedAt()),
                log.getActor() == null ? "" : log.getActor().getName(),
                log.getDepartment() == null ? "" : log.getDepartment().getName(),
                log.getAction(),
                log.getProject() == null ? "" : log.getProject().getName(),
                log.getRequest() == null ? "" : log.getRequest().getId().toString(),
                log.getTaskIds().stream().map(UUID::toString).collect(Collectors.joining(TASK_ID_SEPARATOR)),
                log.getNetwork() == null ? "" : log.getNetwork().name(),
                log.getIp() == null ? "" : log.getIp(),
                // 결과는 동작 코드로 정함: *_FAILED(VERIFICATION_FAILED, TASK_FAILED 등)와 POLICY_MISMATCH는 실패, 그 외는 성공
                isFailure(log.getAction()) ? "실패" : "성공",
                objectMapper.writeValueAsString(log.getDetail()));
    }

    private boolean isFailure(String action) {
        return action.endsWith(FAILED_ACTION_SUFFIX) || action.equals(POLICY_MISMATCH_ACTION);
    }

    // 기간이 모두 있으면 audit-logs_{from}-{to}, 아니면 내보내기 시작 시각으로 이름을 붙임
    private String fileName(AuditExport export, AuditLogFilterRequest filters) {
        String extension = export.getFormat() == ExportFormat.CSV ? ".csv" : ".xlsx";
        if (filters.from() != null && filters.to() != null) {
            return "audit-logs_" + FILE_DATE.format(filters.from()) + "-" + FILE_DATE.format(filters.to()) + extension;
        }
        return "audit-logs_" + FILE_TIME.format(export.getCreatedAt()) + extension;
    }

    private AuditExport findExport(UUID exportId) {
        return auditExportRepository.findById(exportId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "내보내기를 찾을 수 없습니다"));
    }
}
