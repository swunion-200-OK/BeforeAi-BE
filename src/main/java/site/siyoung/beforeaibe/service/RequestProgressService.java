package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.dto.request.ProgressResponse;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.ExecutionEvent;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ExecutionEventType;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.ExecutionEventRepository;
import site.siyoung.beforeaibe.repository.ExecutionLogRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static site.siyoung.beforeaibe.service.RequestStreamService.approvalNodeId;
import static site.siyoung.beforeaibe.service.RequestStreamService.pendingApprovalNodeId;
import static site.siyoung.beforeaibe.service.RequestStreamService.planNodeId;
import static site.siyoung.beforeaibe.service.RequestStreamService.requestNodeId;
import static site.siyoung.beforeaibe.service.RequestStreamService.stepNodeId;
import static site.siyoung.beforeaibe.service.RequestStreamService.taskNodeId;

// 진행 상황 트리 (API 명세서 2.6, 4.9, CORE-14): REQUEST → PLAN → TASK → APPROVAL/STEP.
// STEP은 웹쉘 콜백의 작업 이벤트(TASK_STARTED·SUCCEEDED·FAILED)로만 만들며, 실행 세션마다 작업 하나에 하나씩 둠
// (.docs/webshell-callback-events.md). 웹쉘 서명은 서명 대상 규칙이 정해지지 않아 아직 검증하지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestProgressService {

    private static final List<String> TASK_EVENT_TYPES = List.of(ExecutionEventType.TASK_STARTED.name(),
            ExecutionEventType.TASK_SUCCEEDED.name(), ExecutionEventType.TASK_FAILED.name());

    private final RequestService requestService;
    private final PlanRepository planRepository;
    private final ApprovalRepository approvalRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final UserRepository userRepository;
    private final RequestStreamService requestStreamService;

    public ProgressResponse getProgress(UUID userId, UUID requestId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        List<Plan> plans = planRepository.findByRequest_IdOrderByCreatedAtAscIdAsc(request.getId());

        // 최신 Plan이 먼저 오도록 역순으로 두고, 라벨 번호는 생성 순서(v1부터)
        List<ProgressNodeResponse> planNodes = new ArrayList<>();
        for (int i = plans.size() - 1; i >= 0; i--) {
            Plan plan = plans.get(i);
            String label = "Plan v" + (i + 1);
            if (plan.getId().equals(request.getCurrentPlanId())) {
                planNodes.add(ProgressNodeResponse.currentPlan(planNodeId(plan.getId()), label, plan.getPlanHash(),
                        taskNodes(plan)));
            } else {
                planNodes.add(ProgressNodeResponse.supersededPlan(planNodeId(plan.getId()), label));
            }
        }

        ProgressNodeResponse root = ProgressNodeResponse.request(requestNodeId(request.getId()), request.getPrompt(),
                request.getStatus().name(), planNodes);
        return new ProgressResponse(request.getId(), request.getProgressVersion(), request.getUpdatedAt(), root);
    }

    public SseEmitter subscribeProgress(UUID userId, UUID requestId, Long lastEventId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        return requestStreamService.subscribeProgress(request.getId(), request.getProgressVersion(), lastEventId);
    }

    // 작업은 order 순으로 실행되므로 바로 앞 작업을 dependsOn으로 둠
    private List<ProgressNodeResponse> taskNodes(Plan plan) {
        List<Approval> approvals = approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId());
        Map<UUID, User> approvers = userRepository.findAllById(
                        approvals.stream().map(Approval::getApproverId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Map<UUID, List<ProgressNodeResponse>> stepsByTask = stepNodes(plan);

        List<Task> tasks = plan.getTasks().stream().sorted(Comparator.comparingInt(Task::getOrder)).toList();
        List<ProgressNodeResponse> nodes = new ArrayList<>();
        String previousTaskNodeId = null;
        for (Task task : tasks) {
            List<String> dependsOn = previousTaskNodeId == null ? List.of() : List.of(previousTaskNodeId);
            List<ProgressNodeResponse> children = approvalNodes(task, approvals, approvers);
            children.addAll(stepsByTask.getOrDefault(task.getId(), List.of()));
            nodes.add(ProgressNodeResponse.task(taskNodeId(task.getId()), task.getOrder() + ". " + task.getTitle(),
                    task.getType(), task.getRisk(), task.getStatus().name(), dependsOn, children));
            previousTaskNodeId = taskNodeId(task.getId());
        }
        return nodes;
    }

    // 제출된 승인은 승인자 이름으로, 아직 승인 대기인 작업은 필요 승인권자마다 후보 목록으로 나타냄
    private List<ProgressNodeResponse> approvalNodes(Task task, List<Approval> approvals, Map<UUID, User> approvers) {
        List<ProgressNodeResponse> nodes = new ArrayList<>();
        approvals.stream()
                .filter(approval -> approval.getTaskIds().contains(task.getId()))
                .forEach(approval -> {
                    User approver = approvers.get(approval.getApproverId());
                    String label = approver == null ? approval.getApproverId().toString() : approver.getName();
                    nodes.add(ProgressNodeResponse.approved(approvalNodeId(approval.getId()), label,
                            approval.getNetwork(), approval.getCreatedAt()));
                });
        if (task.getStatus() == TaskStatus.PENDING) {
            List<RequiredApprover> required = task.getRequiredApprovers();
            for (int i = 0; i < required.size(); i++) {
                nodes.add(ProgressNodeResponse.pendingApproval(pendingApprovalNodeId(task.getId(), i),
                        required.get(i).candidates()));
            }
        }
        return nodes;
    }

    // 작업별 STEP 노드. 같은 실행 세션·작업의 이벤트를 하나의 STEP으로 묶고, 종료 이벤트가 있으면 그 상태를 씀
    private Map<UUID, List<ProgressNodeResponse>> stepNodes(Plan plan) {
        List<ExecutionEvent> events = executionEventRepository.findTaskEventsByPlanId(plan.getId(), TASK_EVENT_TYPES);
        if (events.isEmpty()) {
            return Map.of();
        }
        Map<String, ExecutionLogRepository.LogRangeView> logRanges = executionLogRepository
                .findLogRangesByPlanId(plan.getId()).stream()
                .collect(Collectors.toMap(range -> stepNodeId(range.getExecutionId(), range.getTaskId()),
                        Function.identity()));
        Map<UUID, Task> tasksById = plan.getTasks().stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));

        // 첫 이벤트 순서대로 STEP을 둠
        Map<String, StepState> steps = new LinkedHashMap<>();
        for (ExecutionEvent event : events) {
            UUID taskId = event.getTask().getId();
            String stepId = stepNodeId(event.getExecution().getId(), taskId);
            StepState state = steps.computeIfAbsent(stepId, id -> new StepState(taskId));
            state.apply(ExecutionEventType.valueOf(event.getType()), event.getData(), tasksById.get(taskId));
        }

        Map<UUID, List<ProgressNodeResponse>> result = new LinkedHashMap<>();
        steps.forEach((stepId, state) -> {
            ExecutionLogRepository.LogRangeView range = logRanges.get(stepId);
            ProgressNodeResponse.LogRange logRange = range == null ? null
                    : new ProgressNodeResponse.LogRange(range.getFromSequence(),
                    state.running() ? null : range.getToSequence());
            result.computeIfAbsent(state.taskId, id -> new ArrayList<>())
                    .add(ProgressNodeResponse.step(stepId, state.label, state.status.name(), logRange));
        });
        return result;
    }

    // STEP 라벨: 웹쉘이 data.label로 단계 이름을 보내면 그 값, 없으면 작업 제목 (웹쉘 콜백 계약)
    static String stepLabel(Map<String, Object> data, Task task) {
        if (data != null && data.get("label") instanceof String label && !label.isBlank()) {
            return label;
        }
        return task == null ? null : task.getTitle();
    }

    // STEP 상태: 시작 이벤트만 있으면 RUNNING, 종료 이벤트가 있으면 SUCCEEDED/FAILED
    static TaskStatus stepStatus(ExecutionEventType type) {
        return switch (type) {
            case TASK_SUCCEEDED -> TaskStatus.SUCCEEDED;
            case TASK_FAILED -> TaskStatus.FAILED;
            default -> TaskStatus.RUNNING;
        };
    }

    private static final class StepState {

        private final UUID taskId;
        private String label;
        private TaskStatus status = TaskStatus.RUNNING;

        private StepState(UUID taskId) {
            this.taskId = taskId;
        }

        private void apply(ExecutionEventType type, Map<String, Object> data, Task task) {
            if (label == null) {
                label = stepLabel(data, task);
            }
            // 종료 상태가 정해진 뒤 늦게 온 시작 이벤트로 RUNNING에 되돌아가지 않음
            if (type != ExecutionEventType.TASK_STARTED || running()) {
                status = stepStatus(type);
            }
        }

        private boolean running() {
            return status == TaskStatus.RUNNING;
        }
    }
}
