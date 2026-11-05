package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.ai.RequestAnalysisDispatcher;
import site.siyoung.beforeaibe.dto.request.PlanChangeRequest;
import site.siyoung.beforeaibe.dto.request.PlanPatchRequest;
import site.siyoung.beforeaibe.dto.request.PlanRetryResponse;
import site.siyoung.beforeaibe.dto.request.RequestStatusResponse;
import site.siyoung.beforeaibe.dto.request.SubmitResponse;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PlanRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// 요청자의 Plan 작업: AI 분석 재시도(CORE-01), Plan 수정·답변(EMP-03), 제출(6.2), 실패 작업 재시도(6.2)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestPlanService {

    // 명세: 분석은 3회까지. analysisAttempts는 최초 분석 1회를 포함한 횟수
    private static final int MAX_ANALYSIS_ATTEMPTS = 3;
    private static final Set<RequestStatus> PLAN_EDITABLE = EnumSet.of(
            RequestStatus.REVIEWING, RequestStatus.PENDING_APPROVAL, RequestStatus.PARTIALLY_COMPLETED);
    private static final Set<RequestStatus> SUBMITTABLE = EnumSet.of(
            RequestStatus.REVIEWING, RequestStatus.REAPPROVAL_REQUIRED, RequestStatus.VERIFICATION_FAILED);
    private static final Set<TaskStatus> RETRYABLE_TASK = EnumSet.of(TaskStatus.FAILED, TaskStatus.SKIPPED);

    private final RequestService requestService;
    private final PlanRepository planRepository;
    private final PlanHashService planHashService;
    private final RequestStreamService requestStreamService;
    private final RequestAnalysisDispatcher analysisDispatcher;

    @Transactional
    public RequestStatusResponse retryAnalysis(UUID userId, UUID requestId) {
        Request request = requestService.getRequesterRequestForUpdate(requestId, userId);
        if (request.getStatus() != RequestStatus.ANALYSIS_FAILED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "분석에 실패한 요청만 다시 분석할 수 있습니다");
        }
        if (request.getAnalysisAttempts() >= MAX_ANALYSIS_ATTEMPTS) {
            throw new ApiException(ErrorCode.RETRY_LIMIT);
        }
        request.retryAnalysis();
        // 커밋 후 비동기 재분석 시작
        analysisDispatcher.dispatchAfterCommit(request.getId());
        requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(request.getId()),
                request.getStatus().name());
        return RequestStatusResponse.from(request);
    }

    // DELETE는 그 작업을 뺀 새 Plan을 만들어 바로 반영하고, UPDATE·answer는 재분석 입력으로 넘김. 응답 상태는 ANALYZING
    @Transactional
    public RequestStatusResponse patchPlan(UUID userId, UUID requestId, PlanPatchRequest patch) {
        List<PlanChangeRequest> changes = patch.changes() == null ? List.of() : patch.changes();
        boolean hasAnswer = patch.answer() != null && !patch.answer().isBlank();
        if (changes.isEmpty() && !hasAnswer) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "changes 또는 answer 중 하나는 있어야 합니다");
        }

        Request request = requestService.getRequesterRequestForUpdate(requestId, userId);
        if (!PLAN_EDITABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 상태에서는 Plan을 수정할 수 없습니다");
        }
        if (!Objects.equals(patch.basePlanId(), request.getCurrentPlanId())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "basePlanId가 현재 Plan과 다릅니다");
        }

        UUID planId = request.getCurrentPlanId();
        boolean planReplaced = false;
        if (!changes.isEmpty()) {
            Plan current = requestService.getCurrentPlan(request);
            validateChanges(current, changes);
            Set<UUID> deleteTaskIds = changes.stream()
                    .filter(change -> change.op() == PlanChangeRequest.Op.DELETE)
                    .map(PlanChangeRequest::taskId)
                    .collect(Collectors.toSet());
            if (!deleteTaskIds.isEmpty()) {
                // 기존 승인 서명은 모두 무효가 되므로 APPROVED였던 작업도 다시 승인 대기로 둠
                Plan revised = copyPlan(request, current, task -> !deleteTaskIds.contains(task.getId()),
                        task -> task.getStatus() == TaskStatus.APPROVED ? TaskStatus.PENDING : task.getStatus());
                planId = revised.getId();
                planReplaced = true;
            }
        }

        // UPDATE의 instruction과 answer는 재분석 입력. answer는 분석기가 읽도록 clarification에 담아 두고,
        // 분석이 성공하면 Plan 생성 시점에 해소된다(되물음이 없으면 clarification은 유지되지 않음).
        if (hasAnswer) {
            request.askClarification(patch.answer());
        }
        request.startReanalysis(planId);
        // 커밋 후 비동기 재분석 시작
        analysisDispatcher.dispatchAfterCommit(request.getId());
        if (planReplaced) {
            requestStreamService.publishTreeReplaced(request);
        } else {
            requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(request.getId()),
                    request.getStatus().name());
        }
        return RequestStatusResponse.from(request);
    }

    // 웹 서비스가 Plan 해시를 만들어 돌려줌. 웹쉘은 이 단계에서 호출하지 않음 (API 명세서 4.3)
    @Transactional
    public SubmitResponse submit(UUID userId, UUID requestId) {
        Request request = requestService.getRequesterRequestForUpdate(requestId, userId);
        if (!SUBMITTABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 상태에서는 Plan을 제출할 수 없습니다");
        }
        Plan plan = requestService.getCurrentPlan(request);
        if (plan.getTasks().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "제출할 작업이 없습니다");
        }
        if (plan.getTasks().stream().allMatch(task -> task.getRisk() == Risk.FORBIDDEN)) {
            throw new ApiException(ErrorCode.FORBIDDEN_TASK, "제출 가능한 작업이 없고 금지 작업만 남아 있습니다");
        }

        plan.assignHash(planHashService.hash(request, plan));
        request.submit();
        requestStreamService.publishTreeReplaced(request);
        return new SubmitResponse(request.getId(), request.getStatus(), plan.getId(), plan.getPlanHash(),
                selfApprovableTaskIds(plan, request.getRequesterId()));
    }

    // 부분 완료 요청의 실패·건너뛴 작업으로 새 Plan을 만들고 새 해시를 발급. 이전 서명은 재사용할 수 없음
    @Transactional
    public PlanRetryResponse retryFailedTasks(UUID userId, UUID requestId) {
        Request request = requestService.getRequesterRequestForUpdate(requestId, userId);
        if (request.getStatus() != RequestStatus.PARTIALLY_COMPLETED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "부분 완료된 요청만 재시도할 수 있습니다");
        }
        Plan current = requestService.getCurrentPlan(request);
        if (current.getTasks().stream().noneMatch(task -> RETRYABLE_TASK.contains(task.getStatus()))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "재시도할 작업이 없습니다");
        }

        Plan retryPlan = copyPlan(request, current, task -> RETRYABLE_TASK.contains(task.getStatus()),
                task -> TaskStatus.PENDING);
        retryPlan.assignHash(planHashService.hash(request, retryPlan));
        request.retryFailedTasks(retryPlan.getId());
        requestStreamService.publishTreeReplaced(request);
        return new PlanRetryResponse(request.getId(), request.getStatus(), retryPlan.getId(), retryPlan.getPlanHash());
    }

    private void validateChanges(Plan plan, List<PlanChangeRequest> changes) {
        Set<UUID> taskIds = plan.getTasks().stream().map(Task::getId).collect(Collectors.toSet());
        for (PlanChangeRequest change : changes) {
            if (!taskIds.contains(change.taskId())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "현재 Plan에 없는 작업입니다");
            }
            if (change.op() == PlanChangeRequest.Op.UPDATE
                    && (change.instruction() == null || change.instruction().isBlank())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "UPDATE에는 instruction이 필요합니다");
            }
        }
    }

    // 저장소·기준 커밋은 그대로 두고 고른 작업만 새 Plan으로 옮김. 지난 Plan은 진행 트리에 SUPERSEDED로 남음
    private Plan copyPlan(Request request, Plan source, Predicate<Task> include, Function<Task, TaskStatus> status) {
        Plan copy = Plan.builder()
                .request(request)
                .kind(source.getKind())
                .repository(source.getRepository())
                .baseCommit(source.getBaseCommit())
                .build();
        source.getTasks().stream()
                .filter(include)
                .forEach(task -> copy.addTask(task.copy(status.apply(task))));
        return planRepository.save(copy);
    }

    // 요청자 본인이 승인할 수 있는 작업: 금지 작업이 아니고, 필요 승인권자 중 ANYONE이 있거나 후보 목록에 본인이 있음
    private List<UUID> selfApprovableTaskIds(Plan plan, UUID requesterId) {
        return plan.getTasks().stream()
                .filter(task -> task.getRisk() != Risk.FORBIDDEN)
                .filter(task -> task.getRequiredApprovers().stream().anyMatch(approver ->
                        approver.rule() == ApproverRule.ANYONE
                                || (approver.candidates() != null && approver.candidates().contains(requesterId))))
                .map(Task::getId)
                .toList();
    }
}
