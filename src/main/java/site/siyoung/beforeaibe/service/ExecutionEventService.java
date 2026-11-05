package site.siyoung.beforeaibe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.execution.ExecutionEventRequest;
import site.siyoung.beforeaibe.dto.request.ExecutionLogResponse;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionEvent;
import site.siyoung.beforeaibe.entity.ExecutionLog;
import site.siyoung.beforeaibe.entity.Plan;
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
import site.siyoung.beforeaibe.support.Ed25519SignatureVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 웹쉘 서명 실행 이벤트 콜백 (5장 콜백, POST /internal/executions/{executionId}/events).
// 웹쉘 서명(Ed25519, 본문에서 webshellSignature를 뺀 객체의 JCS)이 확인된 이벤트만 반영해 웹 서비스가 결과를 지어낼 수 없게 함
@Service
public class ExecutionEventService {

    // 상태를 바꾸는 이벤트를 받을 수 있는 요청 상태 (API 명세서 6장 웹쉘 콜백 행)
    private static final Set<RequestStatus> EXECUTING = EnumSet.of(RequestStatus.RUNNING, RequestStatus.ROLLING_BACK);
    // 아직 끝나지 않은 작업. 실행이 끝났는데 이 상태면 실행되지 않은 것으로 봄
    private static final Set<TaskStatus> UNFINISHED = EnumSet.of(TaskStatus.APPROVED, TaskStatus.RUNNING);
    private static final Set<ExecutionStatus> FINAL_STATUSES = EnumSet.of(
            ExecutionStatus.COMPLETED, ExecutionStatus.PARTIALLY_COMPLETED, ExecutionStatus.FAILED);

    private final RequestRepository requestRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final PlanHashService planHashService;
    private final Ed25519SignatureVerifier signatureVerifier;
    private final RequestStreamService requestStreamService;
    private final String signingPublicKey;

    public ExecutionEventService(RequestRepository requestRepository,
                                 ExecutionRepository executionRepository,
                                 ExecutionEventRepository executionEventRepository,
                                 ExecutionLogRepository executionLogRepository,
                                 PlanHashService planHashService,
                                 Ed25519SignatureVerifier signatureVerifier,
                                 RequestStreamService requestStreamService,
                                 @Value("${webshell.signing-public-key:}") String signingPublicKey) {
        this.requestRepository = requestRepository;
        this.executionRepository = executionRepository;
        this.executionEventRepository = executionEventRepository;
        this.executionLogRepository = executionLogRepository;
        this.planHashService = planHashService;
        this.signatureVerifier = signatureVerifier;
        this.requestStreamService = requestStreamService;
        this.signingPublicKey = signingPublicKey;
    }

    @Transactional
    public void receive(UUID executionId, ExecutionEventRequest event) {
        // DB를 보기 전에 서명부터 확인함
        verifySignature(event);

        UUID eventId = uuid(event.eventId(), "eventId");
        ExecutionEventType type = type(event.type());
        if (!executionId.equals(uuid(event.executionId(), "executionId"))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "경로와 본문의 executionId가 다릅니다");
        }
        UUID requestId = uuid(event.requestId(), "requestId");
        UUID taskId = event.taskId() == null ? null : uuid(event.taskId(), "taskId");
        Instant at = instant(event.at());
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();

        // 킬 스위치·다른 이벤트와 같은 요청 행 잠금으로 순서를 맞추고, 실행 세션은 잠근 뒤에 읽어 최신 상태를 봄
        Request request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "요청을 찾을 수 없습니다"));
        Execution execution = executionRepository.findByWebshellExecutionId(executionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "실행을 찾을 수 없습니다"));
        if (!execution.getRequest().getId().equals(request.getId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "실행과 요청이 맞지 않습니다");
        }
        // 웹쉘이 다시 보낸 이벤트는 이미 반영했으므로 성공으로 응답
        if (executionEventRepository.existsByEventId(eventId)) {
            return;
        }
        Task task = taskId == null ? null : findTask(execution.getPlan(), taskId);

        switch (type) {
            case TASK_STARTED, TASK_SUCCEEDED, TASK_FAILED -> {
                requireExecuting(request, execution);
                if (task == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "작업 이벤트에는 taskId가 필요합니다");
                }
            }
            case EXECUTION_FINISHED -> requireExecuting(request, execution);
            case LOG, ALERT -> {
            }
        }

        executionEventRepository.save(ExecutionEvent.builder()
                .eventId(eventId)
                .execution(execution)
                .type(type.name())
                .task(task)
                .occurredAt(at)
                .data(data)
                .webshellSignature(event.webshellSignature())
                .build());

        switch (type) {
            case TASK_STARTED -> taskStarted(request, task, data);
            case TASK_SUCCEEDED -> taskFinished(request, task, TaskStatus.SUCCEEDED);
            case TASK_FAILED -> taskFinished(request, task, TaskStatus.FAILED);
            case LOG -> log(request, execution, task, at, data, event.webshellSignature());
            case EXECUTION_FINISHED -> executionFinished(request, execution, data);
            // 검증 실패·정책 불일치 경보는 이벤트로만 남김
            case ALERT -> {
            }
        }
    }

    private void taskStarted(Request request, Task task, Map<String, Object> data) {
        if (task.getStatus() != TaskStatus.APPROVED) {
            return;
        }
        task.changeStatus(TaskStatus.RUNNING);
        String taskNodeId = RequestStreamService.taskNodeId(task.getId());
        requestStreamService.publishNodeUpdated(request, taskNodeId, task.getStatus().name());
        requestStreamService.publishNodeAdded(request, taskNodeId, ProgressNodeResponse.step(
                RequestStreamService.stepNodeId(task.getId()), RequestProgressService.stepLabel(task, data),
                TaskStatus.RUNNING.name(), null));
    }

    // 시작 이벤트 없이 끝 이벤트가 먼저 와도 반영함. 이미 끝난 작업은 바꾸지 않음
    private void taskFinished(Request request, Task task, TaskStatus status) {
        if (!UNFINISHED.contains(task.getStatus())) {
            return;
        }
        task.changeStatus(status);
        if (!executionEventRepository.existsByTask_IdAndType(task.getId(), ExecutionEventType.TASK_STARTED.name())) {
            // STEP 노드가 아직 없으므로 트리를 다시 받게 함
            requestStreamService.publishTreeReplaced(request);
            return;
        }
        requestStreamService.publishNodeUpdated(request, RequestStreamService.taskNodeId(task.getId()), status.name());
        requestStreamService.publishNodeUpdated(request, RequestStreamService.stepNodeId(task.getId()), status.name());
    }

    private void log(Request request, Execution execution, Task task, Instant at, Map<String, Object> data,
                     String webshellSignature) {
        long sequence = switch (data.get("sequence")) {
            case Integer value when value >= 0 -> value;
            case Long value when value >= 0 -> value;
            case null, default -> throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "LOG 이벤트에는 0 이상의 정수 data.sequence가 필요합니다");
        };
        if (!(data.get("message") instanceof String message)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "LOG 이벤트에는 문자열 data.message가 필요합니다");
        }
        // 다른 eventId로 같은 줄이 다시 오면 처음 것만 둠
        if (executionLogRepository.existsByExecution_IdAndSequence(execution.getId(), sequence)) {
            return;
        }
        ExecutionLog saved = executionLogRepository.save(ExecutionLog.builder()
                .execution(execution)
                .request(request)
                .task(task)
                .sequence(sequence)
                .loggedAt(at)
                .message(message)
                .webshellSignature(webshellSignature)
                .build());
        requestStreamService.publishLog(request.getId(), ExecutionLogResponse.from(saved));
    }

    // 최종 상태는 웹쉘이 보낸 값을 씀. 롤백 Plan이면 성공은 ROLLED_BACK, 그 외는 ROLLBACK_FAILED
    private void executionFinished(Request request, Execution execution, Map<String, Object> data) {
        ExecutionStatus status = finalStatus(data.get("status"));
        execution.changeStatus(status);
        Plan plan = execution.getPlan();
        for (Task task : plan.getTasks()) {
            if (UNFINISHED.contains(task.getStatus())) {
                task.changeStatus(TaskStatus.SKIPPED);
            }
        }
        if (plan.getKind() == PlanKind.ROLLBACK) {
            request.finishExecution(status == ExecutionStatus.COMPLETED
                    ? RequestStatus.ROLLED_BACK : RequestStatus.ROLLBACK_FAILED);
        } else {
            request.finishExecution(RequestStatus.valueOf(status.name()));
        }
        requestStreamService.publishTreeReplaced(request);
    }

    private ExecutionStatus finalStatus(Object value) {
        if (value instanceof String text) {
            for (ExecutionStatus status : FINAL_STATUSES) {
                if (status.name().equals(text)) {
                    return status;
                }
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR,
                "EXECUTION_FINISHED 이벤트의 data.status는 COMPLETED, PARTIALLY_COMPLETED, FAILED 중 하나여야 합니다");
    }

    private void requireExecuting(Request request, Execution execution) {
        if (!EXECUTING.contains(request.getStatus()) || execution.getStatus() != ExecutionStatus.RUNNING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "실행 중인 요청만 실행 결과를 반영할 수 있습니다");
        }
    }

    private void verifySignature(ExecutionEventRequest event) {
        if (signingPublicKey == null || signingPublicKey.isBlank()) {
            // 설정 누락. 웹쉘은 실패 응답을 받고 다시 보낼 수 있음
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "웹쉘 서명 공개키가 설정되지 않았습니다");
        }
        String canonical;
        try {
            canonical = planHashService.canonicalize(event.signedContent());
        } catch (IllegalArgumentException e) {
            // JCS 정규화 규칙에 없는 값(소수 등)은 서명 대상으로 쓰지 않음
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "서명 대상에 쓸 수 없는 값이 있습니다");
        }
        Ed25519SignatureVerifier.Result result = signatureVerifier.verify(signingPublicKey,
                canonical.getBytes(StandardCharsets.UTF_8), event.webshellSignature());
        if (result != Ed25519SignatureVerifier.Result.VALID) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "웹쉘 서명을 확인할 수 없습니다");
        }
    }

    private Task findTask(Plan plan, UUID taskId) {
        return plan.getTasks().stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "실행한 Plan에 없는 작업입니다"));
    }

    private static ExecutionEventType type(String value) {
        try {
            return ExecutionEventType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "알 수 없는 이벤트 종류입니다: " + value);
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, field + " 형식이 잘못되었습니다");
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "at 형식이 잘못되었습니다");
        }
    }
}
