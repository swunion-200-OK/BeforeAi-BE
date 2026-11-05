package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.request.ExecutionLogResponse;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.dto.webshell.ExecutionEventRequest;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionEvent;
import site.siyoung.beforeaibe.entity.ExecutionLog;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ExecutionEventType;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ExecutionEventRepository;
import site.siyoung.beforeaibe.repository.ExecutionLogRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static site.siyoung.beforeaibe.service.RequestStreamService.stepNodeId;
import static site.siyoung.beforeaibe.service.RequestStreamService.taskNodeId;

// 웹쉘 실행 이벤트 콜백 처리 (API 명세서 5 콜백). 명세에 이벤트 계약이 없어 서버에서 정한 규칙을 따름.
// 규칙 전체는 .docs/webshell-callback-events.md에 있고, 웹쉘 서명은 서명 대상 규칙이 없어 저장만 하고 검증하지 않음
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEventService {

    private static final Set<TaskStatus> NOT_STARTED = EnumSet.of(TaskStatus.PENDING, TaskStatus.APPROVED);
    private static final Set<TaskStatus> FINISHABLE = EnumSet.of(TaskStatus.APPROVED, TaskStatus.RUNNING);
    private static final Set<RequestStatus> EXECUTING = EnumSet.of(RequestStatus.RUNNING, RequestStatus.ROLLING_BACK);
    private static final Set<ExecutionStatus> FINAL_STATUSES = EnumSet.of(
            ExecutionStatus.COMPLETED, ExecutionStatus.PARTIALLY_COMPLETED, ExecutionStatus.FAILED);

    private final ExecutionRepository executionRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final RequestRepository requestRepository;
    private final RequestStreamService requestStreamService;
    private final RequestStatusEventPublisher requestStatusEventPublisher;
    private final AuditWriter auditWriter;

    @Transactional
    public void receive(UUID webshellExecutionId, ExecutionEventRequest body) {
        if (!webshellExecutionId.equals(body.executionId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "경로와 본문의 executionId가 다릅니다");
        }
        UUID requestId = executionRepository.findRequestIdByWebshellExecutionId(webshellExecutionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "실행 세션을 찾을 수 없습니다"));
        if (!requestId.equals(body.requestId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "실행 세션의 요청과 requestId가 다릅니다");
        }
        // 같은 요청의 이벤트·중단 요청을 직렬화함. 실행 세션은 잠근 뒤에 읽어야 최신 상태를 봄
        Request request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "요청을 찾을 수 없습니다"));
        Execution execution = executionRepository.findByWebshellExecutionId(webshellExecutionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "실행 세션을 찾을 수 없습니다"));
        // 이미 반영한 이벤트를 다시 받으면 아무것도 하지 않음 (웹쉘 재전송)
        if (executionEventRepository.existsByEventId(body.eventId())) {
            return;
        }

        ExecutionEventType type = parseType(body.type());
        Map<String, Object> data = body.data() == null ? Map.of() : body.data();
        Task task = findTask(execution, body.taskId());
        if (!type.isAlert() && execution.getStatus() != ExecutionStatus.RUNNING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "실행 중인 세션이 아닙니다");
        }

        switch (type) {
            case TASK_STARTED -> changeTask(request, execution, requireTask(task), data, NOT_STARTED,
                    TaskStatus.RUNNING);
            case TASK_SUCCEEDED -> changeTask(request, execution, requireTask(task), data, FINISHABLE,
                    TaskStatus.SUCCEEDED);
            case TASK_FAILED -> changeTask(request, execution, requireTask(task), data, FINISHABLE,
                    TaskStatus.FAILED);
            case LOG -> saveLog(execution, request, task, body, data);
            case EXECUTION_FINISHED -> finish(execution, request, data);
            case VERIFICATION_ALERT, POLICY_MISMATCH -> log.warn("웹쉘 경보 수신: type={}, executionId={}, data={}",
                    type, webshellExecutionId, data);
        }

        executionEventRepository.save(ExecutionEvent.builder()
                .eventId(body.eventId())
                .execution(execution)
                .type(type.name())
                .task(task)
                .occurredAt(body.at())
                .data(data)
                .webshellSignature(body.webshellSignature())
                .build());
        recordAudit(type, execution, requestId, body, task);
    }

    // 웹쉘 콜백은 감사 인터셉터 대상이 아니라 여기서 직접 남김. 커밋 뒤에 기록해 롤백된 이벤트는 남지 않음.
    // data 원문은 민감할 수 있어 detail에 넣지 않음
    private void recordAudit(ExecutionEventType type, Execution execution, UUID requestId,
                             ExecutionEventRequest body, Task task) {
        String action = switch (type) {
            case TASK_SUCCEEDED -> "TASK_SUCCEEDED";
            case TASK_FAILED -> "TASK_FAILED";
            case VERIFICATION_ALERT -> "VERIFICATION_FAILED";
            case POLICY_MISMATCH -> "POLICY_MISMATCH";
            case EXECUTION_FINISHED -> execution.getPlan().getKind() != PlanKind.ROLLBACK ? null
                    : execution.getStatus() == ExecutionStatus.COMPLETED ? "ROLLBACK_SUCCEEDED" : "ROLLBACK_FAILED";
            case TASK_STARTED, LOG -> null;
        };
        if (action == null) {
            return;
        }
        auditWriter.recordAfterCommit(null, action, requestId,
                task == null ? List.of() : List.of(task.getId()), null, null,
                Map.of("eventId", body.eventId().toString(), "type", type.name(),
                        "executionId", body.executionId().toString()));
    }

    private static ExecutionEventType parseType(String type) {
        try {
            return ExecutionEventType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 이벤트 종류입니다");
        }
    }

    // taskId가 있으면 이 실행 세션의 Plan 작업이어야 함
    private static Task findTask(Execution execution, UUID taskId) {
        if (taskId == null) {
            return null;
        }
        return execution.getPlan().getTasks().stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "실행 Plan에 없는 작업입니다"));
    }

    private static Task requireTask(Task task) {
        if (task == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "taskId가 필요합니다");
        }
        return task;
    }

    // 작업 상태를 바꾸고 진행 트리의 TASK·STEP 노드를 알림.
    // 시작 이벤트를 받은(RUNNING) 작업은 STEP이 이미 있으므로 갱신하고, 없으면 STEP을 새로 추가함
    private void changeTask(Request request, Execution execution, Task task, Map<String, Object> data,
                            Set<TaskStatus> allowed, TaskStatus next) {
        TaskStatus previous = task.getStatus();
        if (!allowed.contains(previous)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 작업 상태에서 받을 수 없는 이벤트입니다");
        }
        task.changeStatus(next);
        requestStreamService.publishNodeUpdated(request, taskNodeId(task.getId()), next.name());

        String stepId = stepNodeId(execution.getId(), task.getId());
        if (previous == TaskStatus.RUNNING) {
            requestStreamService.publishNodeUpdated(request, stepId, next.name());
        } else {
            requestStreamService.publishNodeAdded(request, taskNodeId(task.getId()), ProgressNodeResponse.step(
                    stepId, RequestProgressService.stepLabel(data, task), next.name(), null));
        }
    }

    private void saveLog(Execution execution, Request request, Task task, ExecutionEventRequest body,
                         Map<String, Object> data) {
        if (!(data.get("sequence") instanceof Number sequence) || !(data.get("message") instanceof String message)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "로그 이벤트에는 data.sequence와 data.message가 필요합니다");
        }
        ExecutionLog saved = executionLogRepository.save(ExecutionLog.builder()
                .execution(execution)
                .request(request)
                .task(task)
                .sequence(sequence.longValue())
                .loggedAt(body.at())
                .message(message)
                .webshellSignature(body.webshellSignature())
                .build());
        requestStreamService.publishLog(request.getId(), ExecutionLogResponse.from(saved));
    }

    // 작업 결과를 서버가 지어내지 않도록, 종료 이벤트를 받지 않은(RUNNING) 작업이 남아 있으면 최종 상태를 받지 않음
    private void finish(Execution execution, Request request, Map<String, Object> data) {
        ExecutionStatus finalStatus = parseFinalStatus(data.get("status"));
        if (!EXECUTING.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "실행 중인 요청이 아닙니다");
        }
        if (execution.getPlan().getTasks().stream().anyMatch(task -> task.getStatus() == TaskStatus.RUNNING)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "종료 이벤트를 받지 않은 작업이 있습니다");
        }
        execution.changeStatus(finalStatus);
        RequestStatus previousStatus = request.getStatus();
        request.finishExecution(requestStatus(execution.getPlan().getKind(), finalStatus));
        for (Task task : execution.getPlan().getTasks()) {
            if (NOT_STARTED.contains(task.getStatus())) {
                task.changeStatus(TaskStatus.SKIPPED);
            }
        }
        requestStreamService.publishTreeReplaced(request);
        requestStatusEventPublisher.publish(request, previousStatus, null);
    }

    private static ExecutionStatus parseFinalStatus(Object value) {
        if (value instanceof String status) {
            try {
                ExecutionStatus parsed = ExecutionStatus.valueOf(status);
                if (FINAL_STATUSES.contains(parsed)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // 아래에서 같은 오류로 응답
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "data.status는 COMPLETED, PARTIALLY_COMPLETED, FAILED 중 하나여야 합니다");
    }

    // 문서 롤백 Plan은 ROLLED_BACK/ROLLBACK_FAILED로 끝남 (API 명세서 6장)
    private static RequestStatus requestStatus(PlanKind kind, ExecutionStatus finalStatus) {
        if (kind == PlanKind.ROLLBACK) {
            return finalStatus == ExecutionStatus.COMPLETED ? RequestStatus.ROLLED_BACK : RequestStatus.ROLLBACK_FAILED;
        }
        return RequestStatus.valueOf(finalStatus.name());
    }
}
