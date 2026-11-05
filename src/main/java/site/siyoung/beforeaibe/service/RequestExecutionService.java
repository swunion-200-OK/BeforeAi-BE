package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.request.ExecutionLogResponse;
import site.siyoung.beforeaibe.dto.request.KillRequest;
import site.siyoung.beforeaibe.dto.request.KillResponse;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionLog;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ExecutionLogRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// 킬 스위치(CORE-11)와 웹쉘 서명이 붙은 실행 로그 조회(CORE-03)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestExecutionService {

    // 아직 시작하지 않아 중단으로 실행되지 않는 작업
    private static final Set<TaskStatus> NOT_STARTED = EnumSet.of(TaskStatus.PENDING, TaskStatus.APPROVED);

    private final RequestService requestService;
    private final ExecutionRepository executionRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final WebshellClient webshellClient;
    private final RequestStreamService requestStreamService;

    // 승인 서명 없이 즉시 중단하며 외부망에서도 허용함 (API 명세서 4.6)
    @Transactional
    public KillResponse kill(UUID userId, UUID requestId, KillRequest killRequest) {
        Request request = requestService.getViewableRequestForUpdate(requestId, userId);
        if (request.getStatus() != RequestStatus.RUNNING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "실행 중인 요청만 중단할 수 있습니다");
        }
        Execution execution = executionRepository
                .findFirstByRequest_IdAndStatusOrderByCreatedAtDesc(request.getId(), ExecutionStatus.RUNNING)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE, "실행 중인 웹쉘 세션이 없습니다"));
        String reason = killRequest == null ? null : killRequest.reason();

        // 웹쉘이 중단을 받아들인 뒤에만 상태를 바꿈. 실패하면 502이고 트랜잭션은 롤백됨
        webshellClient.kill(execution.getWebshellExecutionId(), reason);

        execution.kill(reason);
        request.kill();
        List<UUID> skippedTaskIds = new ArrayList<>();
        for (Task task : execution.getPlan().getTasks()) {
            if (NOT_STARTED.contains(task.getStatus())) {
                task.changeStatus(TaskStatus.SKIPPED);
                skippedTaskIds.add(task.getId());
            }
        }
        requestStreamService.publishTreeReplaced(request);
        return new KillResponse(request.getId(), request.getStatus(), skippedTaskIds);
    }

    // 목록 API 공통 페이지 규칙(cursor, limit)을 따름. 실행 세션 순서 → sequence 순
    public CursorPageResponse<ExecutionLogResponse> logs(UUID userId, UUID requestId, String cursor, int limit) {
        Request request = requestService.getViewableRequest(requestId, userId);
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<ExecutionLog> logs;
        if (cursor == null || cursor.isBlank()) {
            logs = executionLogRepository.findPage(request.getId(), fetchLimit);
        } else {
            Cursor decoded = Cursor.decode(cursor);
            logs = executionLogRepository.findPageAfter(request.getId(), decoded.executionCreatedAt(),
                    decoded.executionId(), decoded.sequence(), fetchLimit);
        }

        boolean hasNext = logs.size() > limit;
        List<ExecutionLog> page = hasNext ? logs.subList(0, limit) : logs;
        String nextCursor = hasNext ? Cursor.of(page.getLast()).encode() : null;
        return new CursorPageResponse<>(page.stream().map(ExecutionLogResponse::from).toList(), nextCursor);
    }

    public SseEmitter subscribeLogs(UUID userId, UUID requestId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        return requestStreamService.subscribeLogs(request.getId());
    }

    // 로그 커서: 마지막 로그의 (실행 세션 생성 시각, 실행 세션 id, sequence)를 Base64URL로 감싼 값
    private record Cursor(Instant executionCreatedAt, UUID executionId, long sequence) {

        static Cursor of(ExecutionLog log) {
            return new Cursor(log.getExecution().getCreatedAt(), log.getExecution().getId(), log.getSequence());
        }

        static Cursor decode(String value) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 3);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]), Long.parseLong(parts[2]));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
            }
        }

        String encode() {
            String raw = executionCreatedAt + "|" + executionId + "|" + sequence;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
