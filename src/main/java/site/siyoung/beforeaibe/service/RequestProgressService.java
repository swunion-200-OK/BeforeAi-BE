package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.dto.request.ProgressResponse;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.ExecutionEvent;
import site.siyoung.beforeaibe.entity.ExecutionLog;
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
import java.util.Comparator;
import java.util.List;
import java.util.LongSummaryStatistics;
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

// 진행 상황 트리 (API 명세서 2.6, 4.9, CORE-14): REQUEST → PLAN → TASK → APPROVAL·STEP.
// STEP 노드는 웹쉘 서명이 검증된 실행 이벤트(콜백 TASK_*)로만 만들고, 작업 하나에 하나씩 둠
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestProgressService {

    private final RequestService requestService;
    private final PlanRepository planRepository;
    private final ApprovalRepository approvalRepository;
    private final UserRepository userRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final ExecutionLogRepository executionLogRepository;
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

        List<Task> tasks = plan.getTasks().stream().sorted(Comparator.comparingInt(Task::getOrder)).toList();
        List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        Map<UUID, List<ExecutionEvent>> eventsByTask = executionEventRepository
                .findByTask_IdInOrderByOccurredAtAscIdAsc(taskIds).stream()
                .collect(Collectors.groupingBy(event -> event.getTask().getId()));
        Map<UUID, LongSummaryStatistics> logsByTask = executionLogRepository.findByTask_IdIn(taskIds).stream()
                .collect(Collectors.groupingBy(log -> log.getTask().getId(),
                        Collectors.summarizingLong(ExecutionLog::getSequence)));

        List<ProgressNodeResponse> nodes = new ArrayList<>();
        String previousTaskNodeId = null;
        for (Task task : tasks) {
            List<String> dependsOn = previousTaskNodeId == null ? List.of() : List.of(previousTaskNodeId);
            List<ProgressNodeResponse> children = approvalNodes(task, approvals, approvers);
            ProgressNodeResponse step = stepNode(task, eventsByTask.getOrDefault(task.getId(), List.of()),
                    logsByTask.get(task.getId()));
            if (step != null) {
                children.add(step);
            }
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

    // 작업 이벤트(TASK_*)가 하나라도 있으면 STEP 노드를 만듦. 상태는 작업 상태를 따르고(실행 전 상태면 RUNNING),
    // logRange는 그 작업의 실행 로그 sequence 구간이며 실행 중이면 to를 비움
    private ProgressNodeResponse stepNode(Task task, List<ExecutionEvent> events, LongSummaryStatistics logs) {
        List<ExecutionEvent> taskEvents = events.stream()
                .filter(event -> event.getType().startsWith("TASK_"))
                .toList();
        if (taskEvents.isEmpty()) {
            return null;
        }
        String status = switch (task.getStatus()) {
            case SUCCEEDED, FAILED, SKIPPED -> task.getStatus().name();
            default -> TaskStatus.RUNNING.name();
        };
        Map<String, Object> startData = taskEvents.stream()
                .filter(event -> event.getType().equals(ExecutionEventType.TASK_STARTED.name()))
                .findFirst()
                .map(ExecutionEvent::getData)
                .orElse(Map.of());
        ProgressNodeResponse.LogRange logRange = logs == null ? null
                : new ProgressNodeResponse.LogRange(logs.getMin(),
                status.equals(TaskStatus.RUNNING.name()) ? null : logs.getMax());
        return ProgressNodeResponse.step(stepNodeId(task.getId()), stepLabel(task, startData), status, logRange);
    }

    // STEP 라벨: 시작 이벤트의 data.message, 없으면 작업 대상
    static String stepLabel(Task task, Map<String, Object> startData) {
        return startData.get("message") instanceof String message && !message.isBlank() ? message : task.getTarget();
    }
}
