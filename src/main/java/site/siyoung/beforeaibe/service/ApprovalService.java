package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.approval.ApprovalContextResponse;
import site.siyoung.beforeaibe.dto.approval.ApprovalSubmitRequest;
import site.siyoung.beforeaibe.dto.approval.ApprovalSubmitResponse;
import site.siyoung.beforeaibe.dto.approval.PendingApprovalResponse;
import site.siyoung.beforeaibe.dto.approval.RejectionRequest;
import site.siyoung.beforeaibe.dto.approval.RejectionResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.request.PlanResponse;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 승인 (3.6 CORE-04·CORE-05): 내 승인 대기 목록, 승인 전 원문·해시 조회, 암호화 서명 제출, 거부.
// 승인 봉투(envelope)는 웹 서비스에서 열어 보지 않고 웹쉘로 전달만 함. planHash·taskIds·keyId는 사전 검사·표시용
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private static final String INTERNAL_NETWORK_ONLY = ErrorCode.INTERNAL_NETWORK_ONLY.name();

    private final RequestService requestService;
    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final PlanHashService planHashService;
    // 서명 보관 커밋과 웹쉘 실행 요청을 별도 트랜잭션으로 분리하기 위해 별도 빈을 프록시로 호출함(계획서 2.6)
    private final ApprovalTransactionService approvalTransactionService;
    private final ApprovalPolicy approvalPolicy;

    // 내 승인 대기 작업 목록. 승인 받을 수 있는 요청의 현재 Plan에서 내가 승인권자인 PENDING 작업만 평탄화해 내려 줌
    @Transactional(readOnly = true)
    public CursorPageResponse<PendingApprovalResponse> pending(UUID userId, String cursor, int limit) {
        List<Request> requests = requestRepository.findPendingApprovalRequestsForApprover(userId);
        List<PendingApprovalResponse> items = new ArrayList<>();
        for (Request request : requests) {
            Plan plan = requestService.getCurrentPlan(request);
            User requester = findUser(request.getRequesterId());
            plan.getTasks().stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> approvalPolicy.isApprover(task, userId))
                    .forEach(task -> items.add(PendingApprovalResponse.of(request, plan, requester, task)));
        }
        // 커서 페이지네이션은 작업 단위 정렬 키 설계가 끝나면 도입함(Open Question 7.7). 지금은 전체를 한 번에 돌려줌
        return new CursorPageResponse<>(items, null);
    }

    // 승인 전 원문·해시 대상 조회. 외부망이면 내부망 전용 작업을 blockedTaskIds로 돌려줌(조회 자체는 전체 망 허용)
    @Transactional(readOnly = true)
    public ApprovalContextResponse approvalContext(UUID userId, UUID requestId, Network network) {
        Request request = requestService.getViewableRequest(requestId, userId);
        Plan plan = requestService.getCurrentPlan(request);

        Map<String, Object> hashTarget = planHashService.hashTarget(request, plan);
        String planHash = planHashService.hash(hashTarget);

        List<UUID> approvableTaskIds = new ArrayList<>();
        List<UUID> blockedTaskIds = new ArrayList<>();
        for (Task task : plan.getTasks()) {
            if (task.getStatus() != TaskStatus.PENDING || !approvalPolicy.isApprover(task, userId)) {
                continue;
            }
            if (network == Network.EXTERNAL && approvalPolicy.internalOnly(task)) {
                blockedTaskIds.add(task.getId());
            } else {
                approvableTaskIds.add(task.getId());
            }
        }
        String blockedReason = blockedTaskIds.isEmpty() ? null : INTERNAL_NETWORK_ONLY;
        return new ApprovalContextResponse(request.getId(), PlanResponse.from(plan), hashTarget, planHash,
                approvableTaskIds, blockedTaskIds, blockedReason, network);
    }

    // 암호화 서명 제출. 서명 보관·작업 상태 전이는 한 트랜잭션으로 커밋하고(saveApproval),
    // 전량 승인 시 웹쉘 실행 요청은 별도 트랜잭션으로 호출해 연결 실패가 승인 보관을 롤백하지 않도록 함(계획서 2.6)
    // 두 단계 사이에 커밋되어야 하므로 이 퍼사드에는 트랜잭션을 걸지 않음
    public ApprovalSubmitResponse approve(UUID userId, UUID requestId, Network network, ApprovalSubmitRequest body) {
        ApprovalTransactionService.ApproveResult result =
                approvalTransactionService.saveApproval(userId, requestId, network, body);
        if (!result.readyToExecute()) {
            return new ApprovalSubmitResponse(result.requestId(), result.status(),
                    result.approvedTaskIds(), result.remainingTaskIds(), false);
        }
        // 전량 승인됨 → 웹쉘에 실행 요청. 연결 실패면 서명은 이미 보관됐으므로 executionRequested=false로만 응답
        // 실행이 받아들여지면 RUNNING, 문서 롤백 Plan이면 ROLLING_BACK
        boolean executionRequested;
        RequestStatus status;
        try {
            status = approvalTransactionService.requestExecution(requestId, body.planId());
            executionRequested = true;
        } catch (ApiException e) {
            if (e.getErrorCode() != ErrorCode.WEBSHELL_UNAVAILABLE) {
                throw e;
            }
            executionRequested = false;
            status = result.status();
        }
        return new ApprovalSubmitResponse(result.requestId(), status,
                result.approvedTaskIds(), result.remainingTaskIds(), executionRequested);
    }

    public RejectionResponse reject(UUID userId, UUID requestId, RejectionRequest body) {
        return approvalTransactionService.reject(userId, requestId, body);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }
}
