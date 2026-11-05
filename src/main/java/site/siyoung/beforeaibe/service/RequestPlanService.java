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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
    // 전체 실패는 작업 이벤트 없이 끝나 작업이 APPROVED·RUNNING에 머물 수 있으므로 함께 다시 실행함.
    // 부분 완료는 성공 이벤트만 유실됐을 수 있어 실패·건너뛴 작업만 다시 실행함
    private static final Set<TaskStatus> RETRYABLE_AFTER_FAILURE = EnumSet.of(
            TaskStatus.FAILED, TaskStatus.SKIPPED, TaskStatus.APPROVED, TaskStatus.RUNNING);

    private final RequestService requestService;
    private final ApprovalPolicy approvalPolicy;
    private final PlanRepository planRepository;
    private final PlanHashService planHashService;
    private final RequestStreamService requestStreamService;
    private final RequestAnalysisDispatcher analysisDispatcher;
    // 제출 때 결재 문서 판을 같은 트랜잭션에서 만듦
    private final ApprovalDocumentService approvalDocumentService;

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

        // 답변·수정 지시를 재분석 입력으로 보관하고, 되물음(clarification)은 명세대로 비운다.
        String input = reanalysisInput(request, patch, changes);
        if (input != null) {
            request.appendReanalysisInput(input);
        }
        if (hasAnswer) {
            request.clearClarification();
        }
        request.startReanalysis(planId);
        // 커밋 후 비동기 재분석 시작 (입력은 요청에 보관돼 있어 분석 재시도 때도 쓰임)
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

        // 후보가 모자란 작업은 제출해도 영원히 승인될 수 없으므로 여기서 알림
        if (plan.getTasks().stream()
                .filter(task -> task.getRisk() != Risk.FORBIDDEN)
                .anyMatch(task -> !approvalPolicy.hasEnoughCandidates(task))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "승인 후보가 부족한 작업이 있습니다. 담당자나 부서원을 확인해 주세요");
        }

        plan.assignHash(planHashService.hash(request, plan));
        request.submit();
        approvalDocumentService.recordSubmission(request, plan);
        requestStreamService.publishTreeReplaced(request);
        return new SubmitResponse(request.getId(), request.getStatus(), plan.getId(), plan.getPlanHash(),
                selfApprovableTaskIds(plan, request.getRequesterId()));
    }

    // 부분 완료 요청의 실패·건너뛴 작업으로 새 Plan을 만들고 새 해시를 발급. 이전 서명은 재사용할 수 없음
    @Transactional
    public PlanRetryResponse retryFailedTasks(UUID userId, UUID requestId) {
        Request request = requestService.getRequesterRequestForUpdate(requestId, userId);
        if (request.getStatus() != RequestStatus.PARTIALLY_COMPLETED && request.getStatus() != RequestStatus.FAILED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "부분 완료되거나 실패한 요청만 재시도할 수 있습니다");
        }
        Plan current = requestService.getCurrentPlan(request);
        Set<TaskStatus> retryable = request.getStatus() == RequestStatus.FAILED ? RETRYABLE_AFTER_FAILURE : RETRYABLE_TASK;
        Predicate<Task> include = task -> task.getRisk() != Risk.FORBIDDEN && retryable.contains(task.getStatus());
        if (current.getTasks().stream().noneMatch(include)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "재시도할 작업이 없습니다");
        }

        Plan retryPlan = copyPlan(request, current, include, task -> TaskStatus.PENDING);
        retryPlan.markRetry();
        retryPlan.assignHash(planHashService.hash(request, retryPlan));
        request.retryFailedTasks(retryPlan.getId());
        requestStreamService.publishTreeReplaced(request);
        return new PlanRetryResponse(request.getId(), request.getStatus(), retryPlan.getId(), retryPlan.getPlanHash());
    }

    // AI가 맥락을 알도록 되물음과 답변을 함께 남기고, UPDATE 지시는 대상 작업 제목을 붙임
    private String reanalysisInput(Request request, PlanPatchRequest patch, List<PlanChangeRequest> changes) {
        List<String> parts = new ArrayList<>();
        if (patch.answer() != null && !patch.answer().isBlank()) {
            parts.add(request.getClarification() == null ? patch.answer()
                    : "질문: " + request.getClarification() + "\n답변: " + patch.answer());
        }
        List<PlanChangeRequest> updates = changes.stream()
                .filter(change -> change.op() == PlanChangeRequest.Op.UPDATE)
                .toList();
        if (!updates.isEmpty()) {
            Map<UUID, Task> tasks = requestService.getCurrentPlanTasksById(patch.basePlanId());
            parts.add(updates.stream()
                    .map(change -> "- 작업 \"" + tasks.get(change.taskId()).getTitle() + "\" 수정: " + change.instruction())
                    .collect(Collectors.joining("\n")));
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
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
        // 재시도 Plan에서 작업만 뺀 사본도 전체 Plan이 아니므로 결재 문서의 판이 되면 안 됨
        if (source.isRetry()) {
            copy.markRetry();
        }
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
