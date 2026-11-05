package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.approval.ApprovalSubmitRequest;
import site.siyoung.beforeaibe.dto.approval.RejectionRequest;
import site.siyoung.beforeaibe.dto.approval.RejectionResponse;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.value.ApprovalEnvelope;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 승인 서명 보관과 웹쉘 실행 요청의 트랜잭션 경계를 가진 서비스.
// 서명 보관(saveApproval)은 커밋되어야 하고, 웹쉘 실행 요청 실패(502)는 서명 보관을 롤백하면 안 되므로 둘을 나눔(계획서 2.6)
@Service
@RequiredArgsConstructor
public class ApprovalTransactionService {

    // 승인을 받을 수 있는 요청 상태. 그 외(분석 중·실행 중·완료 등)에서는 승인/거부가 INVALID_STATE
    private static final Set<RequestStatus> APPROVABLE = EnumSet.of(
            RequestStatus.PENDING_APPROVAL, RequestStatus.REAPPROVAL_REQUIRED);

    private final RequestService requestService;
    private final RequestRepository requestRepository;
    private final ApprovalRepository approvalRepository;
    private final SigningKeyRepository signingKeyRepository;
    private final ExecutionRepository executionRepository;
    private final PlanHashService planHashService;
    private final RequestStreamService requestStreamService;
    private final WebshellClient webshellClient;
    private final ApprovalPolicy approvalPolicy;

    // 승인 서명 저장 + 작업 상태 전이 (한 트랜잭션). 전량 승인 여부를 ApproveResult로 돌려줌
    @Transactional
    public ApproveResult saveApproval(UUID userId, UUID requestId, Network network, ApprovalSubmitRequest body) {
        Request request = findForUpdate(requestId);
        if (!APPROVABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "승인 대기 중인 요청만 승인할 수 있습니다");
        }
        Plan plan = requestService.getCurrentPlan(request);
        if (!plan.getId().equals(body.planId())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 Plan이 아닙니다");
        }
        // 표시용 해시로 사전 검사만 함. 실제 판단은 웹쉘이 다시 계산한 해시로 함
        if (!planHashService.hash(request, plan).equals(body.planHash())) {
            throw new ApiException(ErrorCode.PLAN_HASH_MISMATCH);
        }
        List<Task> targets = resolveApprovableTargets(plan, body.taskIds(), userId);
        // 내부망 전용 엔드포인트라 여기까지 오면 내부망이지만, 방어적으로 확인
        if (network == Network.EXTERNAL && targets.stream().anyMatch(approvalPolicy::internalOnly)) {
            throw new ApiException(ErrorCode.INTERNAL_NETWORK_ONLY);
        }
        if (!signingKeyRepository.existsByIdAndUserIdAndStatus(body.keyId(), userId, SigningKeyStatus.REGISTERED)) {
            throw new ApiException(ErrorCode.KEY_NOT_REGISTERED);
        }

        ApprovalSubmitRequest.EnvelopeRequest env = body.envelope();
        approvalRepository.save(Approval.builder()
                .request(request)
                .plan(plan)
                .approverId(userId)
                .keyId(body.keyId())
                .planHash(body.planHash())
                .taskIds(body.taskIds())
                .network(network)
                .envelope(ApprovalEnvelope.builder()
                        .alg(env.alg())
                        .encapsulatedKey(env.encapsulatedKey())
                        .ciphertext(env.ciphertext())
                        .build())
                .build());

        // 승인 충족(필요 승인 수 이상)된 작업을 APPROVED로 전이
        List<Approval> approvals = approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId());
        List<UUID> approvedTaskIds = new ArrayList<>();
        for (Task task : targets) {
            if (approvalPolicy.satisfied(task, approvals)) {
                task.changeStatus(TaskStatus.APPROVED);
                approvedTaskIds.add(task.getId());
            }
        }
        requestStreamService.publishTreeReplaced(request);

        // 금지 작업을 뺀 실행 대상이 모두 APPROVED면 실행 가능
        boolean readyToExecute = plan.getTasks().stream()
                .filter(task -> task.getRisk() != Risk.FORBIDDEN)
                .allMatch(task -> task.getStatus() == TaskStatus.APPROVED);
        List<UUID> remainingTaskIds = plan.getTasks().stream()
                .filter(task -> task.getStatus() == TaskStatus.PENDING)
                .map(Task::getId)
                .toList();
        return new ApproveResult(request.getId(), request.getStatus(), approvedTaskIds, remainingTaskIds,
                readyToExecute);
    }

    // 웹쉘 실행 요청 (별도 트랜잭션). 성공하면 Execution 저장·Plan 서명 보관·요청 RUNNING으로 전이.
    // WEBSHELL_UNAVAILABLE이면 이 트랜잭션만 롤백되고 saveApproval 커밋은 유지됨
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestExecution(UUID requestId, UUID expectedPlanId) {
        Request request = findForUpdate(requestId);
        // 승인 저장 커밋 뒤 실행 잠금을 다시 얻기 전까지 요청 취소·Plan 변경이 끼어들 수 있음
        if (!expectedPlanId.equals(request.getCurrentPlanId())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "승인한 Plan이 변경되었습니다");
        }
        // 다른 승인이 먼저 실행을 올렸으면 건너뜀
        if (request.getStatus() == RequestStatus.RUNNING) {
            return;
        }
        if (!APPROVABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "승인 대기 중인 요청만 실행할 수 있습니다");
        }
        Plan plan = requestService.getCurrentPlan(request);
        if (plan.getTasks().stream()
                .filter(task -> task.getRisk() != Risk.FORBIDDEN)
                .anyMatch(task -> task.getStatus() != TaskStatus.APPROVED)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "모든 실행 대상 작업이 승인되어야 합니다");
        }
        Map<String, Object> planPayload = planHashService.hashTarget(request, plan);
        List<WebshellClient.ApprovalPayload> approvalPayloads = approvalRepository
                .findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId()).stream()
                .map(approval -> new WebshellClient.ApprovalPayload(approval.getKeyId(),
                        new WebshellClient.ApprovalPayload.ApprovalEnvelope(
                                approval.getEnvelope().getAlg(),
                                approval.getEnvelope().getEncapsulatedKey(),
                                approval.getEnvelope().getCiphertext())))
                .toList();

        WebshellClient.ExecutionResult result = webshellClient.submitExecution(
                new WebshellClient.ExecutionRequest(planPayload, approvalPayloads));

        plan.assignExecutorSignature(result.webshellSignature());
        executionRepository.save(Execution.builder()
                .request(request)
                .plan(plan)
                .webshellExecutionId(result.executionId())
                .planHash(result.planHash())
                .status(ExecutionStatus.RUNNING)
                .webshellSignature(result.webshellSignature())
                .build());
        request.startRunning();
        requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(request.getId()),
                request.getStatus().name());
    }

    @Transactional
    public RejectionResponse reject(UUID userId, UUID requestId, RejectionRequest body) {
        Request request = findForUpdate(requestId);
        if (!APPROVABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "승인 대기 중인 요청만 거부할 수 있습니다");
        }
        Plan plan = requestService.getCurrentPlan(request);
        if (!plan.getId().equals(body.planId())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 Plan이 아닙니다");
        }
        List<Task> targets = resolveApprovableTargets(plan, body.taskIds(), userId);
        targets.forEach(task -> task.changeStatus(TaskStatus.REJECTED));
        // 거부가 하나라도 생기면 요청 전체를 거부 상태로 둠(거부된 작업은 문서·실행에 반영하지 않음)
        request.reject();
        requestStreamService.publishTreeReplaced(request);
        return new RejectionResponse(request.getId(), request.getStatus());
    }

    // 열람 후보에 없는 ANYONE 승인자도 제출할 수 있으므로, 인가는 resolveApprovableTargets에서 작업별로 검사함
    private Request findForUpdate(UUID requestId) {
        return requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "요청을 찾을 수 없습니다"));
    }

    // 대상 작업이 현재 Plan 소속이고 PENDING이며 내가 승인권자인지 검증
    private List<Task> resolveApprovableTargets(Plan plan, List<UUID> taskIds, UUID userId) {
        Map<UUID, Task> byId = plan.getTasks().stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        List<Task> targets = new ArrayList<>();
        for (UUID taskId : taskIds) {
            Task task = byId.get(taskId);
            if (task == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "현재 Plan에 없는 작업입니다");
            }
            if (task.getStatus() != TaskStatus.PENDING) {
                throw new ApiException(ErrorCode.INVALID_STATE, "승인 대기 중인 작업이 아닙니다");
            }
            if (!approvalPolicy.isApprover(task, userId)) {
                throw new ApiException(ErrorCode.NOT_APPROVER);
            }
            targets.add(task);
        }
        return targets;
    }

    // 서명 저장 트랜잭션 결과. readyToExecute면 전량 승인돼 웹쉘 실행 요청 단계로 넘어감
    public record ApproveResult(
            UUID requestId,
            RequestStatus status,
            List<UUID> approvedTaskIds,
            List<UUID> remainingTaskIds,
            boolean readyToExecute
    ) {
    }
}
