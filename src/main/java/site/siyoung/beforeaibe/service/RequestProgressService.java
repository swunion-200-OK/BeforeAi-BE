package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.request.ProgressNodeResponse;
import site.siyoung.beforeaibe.dto.request.ProgressResponse;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.ArrayList;
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
import static site.siyoung.beforeaibe.service.RequestStreamService.taskNodeId;

// 진행 상황 트리 (API 명세서 2.6, 4.9, CORE-14): REQUEST → PLAN → TASK → APPROVAL.
// STEP 노드는 웹쉘 서명이 검증된 실행 이벤트로만 만드는데, 이벤트 계약(웹쉘 콜백)이 아직 없어 넣지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestProgressService {

    private final RequestService requestService;
    private final PlanRepository planRepository;
    private final ApprovalRepository approvalRepository;
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

        List<Task> tasks = plan.getTasks().stream().sorted(Comparator.comparingInt(Task::getOrder)).toList();
        List<ProgressNodeResponse> nodes = new ArrayList<>();
        String previousTaskNodeId = null;
        for (Task task : tasks) {
            List<String> dependsOn = previousTaskNodeId == null ? List.of() : List.of(previousTaskNodeId);
            nodes.add(ProgressNodeResponse.task(taskNodeId(task.getId()), task.getOrder() + ". " + task.getTitle(),
                    task.getType(), task.getRisk(), task.getStatus().name(), dependsOn,
                    approvalNodes(task, approvals, approvers)));
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
}
