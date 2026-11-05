package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.request.RollbackRequest;
import site.siyoung.beforeaibe.dto.request.RollbackResponse;
import site.siyoung.beforeaibe.entity.Document;
import site.siyoung.beforeaibe.entity.DocumentVersion;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DocumentRepository;
import site.siyoung.beforeaibe.repository.DocumentVersionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 문서 롤백 (3.7, CORE-07): 롤백 Plan(kind=ROLLBACK)을 만들고 해시를 발급함. 이 호출만으로 문서를 복원하지 않고,
// 새 Plan에 새 승인 서명이 모인 뒤 웹쉘에서 실행됨 (ROLLING_BACK → ROLLED_BACK / ROLLBACK_FAILED)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestRollbackService {

    private static final Set<RequestStatus> ROLLBACKABLE = EnumSet.of(
            RequestStatus.COMPLETED, RequestStatus.PARTIALLY_COMPLETED,
            RequestStatus.KILLED, RequestStatus.ROLLBACK_FAILED);

    private final RequestService requestService;
    private final PlanRepository planRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final PlanHashService planHashService;
    private final RequestStreamService requestStreamService;

    @Transactional
    public RollbackResponse rollback(UUID userId, UUID requestId, RollbackRequest rollback) {
        Request request = requestService.getRequestForUpdate(requestId);
        List<Task> tasks = findRequestTasks(request, rollback.taskIds());
        checkRollbackable(request, tasks, userId);
        if (!ROLLBACKABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 상태에서는 이 동작을 수행할 수 없습니다");
        }
        if (tasks.stream().anyMatch(task -> task.getType() != TaskType.DOC)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "문서가 아닌 작업은 롤백할 수 없습니다");
        }
        // 같은 프로젝트에서 경로가 같으면 같은 문서. 한 롤백 Plan에 같은 문서를 두 번 넣지 않음
        if (tasks.stream().map(Task::getTarget).distinct().count() != tasks.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "같은 문서를 가리키는 작업을 함께 롤백할 수 없습니다");
        }

        // 저장소·기준 커밋은 직전 Plan 값을 그대로 씀
        Plan current = requestService.getCurrentPlan(request);
        Plan rollbackPlan = Plan.builder()
                .request(request)
                .kind(PlanKind.ROLLBACK)
                .repository(current.getRepository())
                .baseCommit(current.getBaseCommit())
                .build();
        int order = 1;
        for (Task task : tasks) {
            DocumentVersion target = findTargetVersion(request, task, rollback.targetVersion());
            rollbackPlan.addTask(rollbackTask(order++, task, target));
        }
        planRepository.save(rollbackPlan);

        rollbackPlan.assignHash(planHashService.hash(request, rollbackPlan));
        request.startRollback(rollbackPlan.getId());
        requestStreamService.publishTreeReplaced(request);
        return RollbackResponse.of(request, rollbackPlan);
    }

    // 요청의 모든 Plan(현재·지난 Plan)에서 작업을 찾음. 같은 ID를 두 번 넣으면 한 번으로 셈
    private List<Task> findRequestTasks(Request request, List<UUID> taskIds) {
        Map<UUID, Task> tasksById = planRepository.findByRequest_IdOrderByCreatedAtAscIdAsc(request.getId()).stream()
                .flatMap(plan -> plan.getTasks().stream())
                .collect(Collectors.toMap(Task::getId, Function.identity()));
        return new LinkedHashSet<>(taskIds).stream()
                .map(taskId -> {
                    Task task = tasksById.get(taskId);
                    if (task == null) {
                        throw new ApiException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다");
                    }
                    return task;
                })
                .toList();
    }

    // 롤백할 수 있는 사람: 요청자, 또는 지정한 모든 작업의 승인 후보(requiredApprovers.candidates)
    private void checkRollbackable(Request request, List<Task> tasks, UUID userId) {
        if (request.getRequesterId().equals(userId)) {
            return;
        }
        boolean approver = tasks.stream().allMatch(task -> task.getRequiredApprovers().stream()
                .anyMatch(required -> required.candidates() != null && required.candidates().contains(userId)));
        if (!approver) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "요청자 또는 해당 문서 작업의 승인권자만 할 수 있습니다");
        }
    }

    // DOC 작업의 target(문서 경로)으로 문서를 찾고, 복원할 버전을 가져옴
    private DocumentVersion findTargetVersion(Request request, Task task, int targetVersion) {
        Document document = documentRepository.findByProject_IdAndPath(request.getProjectId(), task.getTarget())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "롤백할 문서가 없습니다"));
        return documentVersionRepository.findByDocument_IdAndVersion(document.getId(), targetVersion)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "문서에 targetVersion 버전이 없습니다"));
    }

    // 원래 DOC 작업의 위험도·관련 부서·필요 승인권자를 그대로 쓰고, 본문만 복원할 버전 내용으로 바꿈.
    // 원본 설명은 바뀐 본문과 맞지 않으므로 explanation은 복사하지 않고 null로 둠
    private static Task rollbackTask(int order, Task source, DocumentVersion target) {
        return Task.builder()
                .order(order)
                .type(TaskType.DOC)
                .risk(source.getRisk())
                .title(source.getTitle())
                .target(source.getTarget())
                .content(new TaskContent(null, null, null, target.getBody()))
                .relatedDepartments(source.getRelatedDepartments())
                .requiredApprovers(source.getRequiredApprovers())
                .status(TaskStatus.PENDING)
                .build();
    }
}
