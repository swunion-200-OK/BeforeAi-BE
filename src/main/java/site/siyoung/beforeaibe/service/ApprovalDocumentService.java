package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalDocumentResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalDocumentVersionSummaryResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalSlotResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.StampResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.TaskResultResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.ApprovalDocument;
import site.siyoung.beforeaibe.entity.ApprovalDocumentVersion;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ApprovalDocumentRepository;
import site.siyoung.beforeaibe.repository.ApprovalDocumentVersionRepository;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.ApprovalDocumentPdfRenderer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 결재 문서: 제출 때 판 생성, 판 목록, 판 미리보기(PDF), 승인 완료 뒤 PDF 다운로드.
// 서명 대상은 PDF가 아니라 Plan 해시이며, 문서는 Plan·Approval·작업 상태를 보여 주기만 함
@Service
@RequiredArgsConstructor
public class ApprovalDocumentService {

    // 실행이 시작된 뒤의 작업 상태. 하나라도 있으면 문서에 실행 결과(최종 상태)를 담음
    private static final Set<TaskStatus> EXECUTED = EnumSet.of(
            TaskStatus.RUNNING, TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.SKIPPED, TaskStatus.ROLLED_BACK);
    // 제출 뒤 이 상태면 요청자가 Plan을 고치는 중(PATCH /plan → 재분석 → 검토, 또는 재승인 필요)
    private static final Set<RequestStatus> REVISING = EnumSet.of(
            RequestStatus.ANALYZING, RequestStatus.ANALYSIS_FAILED, RequestStatus.REVIEWING,
            RequestStatus.REAPPROVAL_REQUIRED);

    private final RequestService requestService;
    private final ApprovalDocumentRepository approvalDocumentRepository;
    private final ApprovalDocumentVersionRepository approvalDocumentVersionRepository;
    private final PlanRepository planRepository;
    private final ApprovalRepository approvalRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalDocumentPdfRenderer pdfRenderer;

    // 제출(submit) 트랜잭션 안에서 판을 만듦. 첫 제출이면 문서와 v1, 바뀐 Plan의 재제출이면 다음 판.
    // 요청 행 잠금(getRequesterRequestForUpdate) 아래에서 불리므로 판 번호가 겹치지 않음
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSubmission(Request request, Plan plan) {
        // 결재 문서는 프롬프트 요청의 일반 Plan만 대상 (에디터 변경·문서 롤백 요청, 실패 작업 재시도 Plan은 대상 아님)
        if (request.getSource() != RequestSource.PROMPT || plan.getKind() != PlanKind.NORMAL || plan.isRetry()) {
            return;
        }
        ApprovalDocument document = approvalDocumentRepository.findByRequest_Id(request.getId()).orElse(null);
        if (document == null) {
            document = approvalDocumentRepository.save(ApprovalDocument.builder()
                    .request(request)
                    .currentVersion(1)
                    .build());
            saveVersion(document, 1, plan);
            return;
        }
        // 같은 Plan을 다시 제출하면(재승인 필요 등) 내용이 같으므로 새 판을 만들지 않음
        if (findVersion(document, document.getCurrentVersion()).getPlan().getId().equals(plan.getId())) {
            return;
        }
        int next = document.getCurrentVersion() + 1;
        saveVersion(document, next, plan);
        document.changeCurrentVersion(next);
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<ApprovalDocumentVersionSummaryResponse> versions(UUID userId, UUID requestId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        ApprovalDocument document = findDocument(request);
        List<ApprovalDocumentVersionSummaryResponse> items = approvalDocumentVersionRepository
                .findByDocument_IdOrderByVersionDesc(document.getId()).stream()
                .map(version -> ApprovalDocumentVersionSummaryResponse.of(version, status(request, document, version)))
                .toList();
        // 판 수가 적어 페이지를 나누지 않음
        return new CursorPageResponse<>(items, null);
    }

    // 최신 판 미리보기. 승인 진행 중에도 볼 수 있음
    @Transactional(readOnly = true)
    public byte[] preview(UUID userId, UUID requestId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        ApprovalDocument document = findDocument(request);
        return pdfRenderer.render(assemble(request, document, findVersion(document, document.getCurrentVersion())));
    }

    // 특정 판 미리보기. 이전 판도 기록으로 볼 수 있음
    @Transactional(readOnly = true)
    public byte[] previewVersion(UUID userId, UUID requestId, int version) {
        Request request = requestService.getViewableRequest(requestId, userId);
        ApprovalDocument document = findDocument(request);
        return pdfRenderer.render(assemble(request, document, findVersion(document, version)));
    }

    // PDF 다운로드. 최신 판의 필요 승인이 모두 끝났을 때만 받을 수 있고, 거부·취소·중단된 요청은 받을 수 없음
    @Transactional(readOnly = true)
    public byte[] download(UUID userId, UUID requestId) {
        Request request = requestService.getViewableRequest(requestId, userId);
        ApprovalDocument document = findDocument(request);
        ApprovalDocumentResponse content = assemble(request, document,
                findVersion(document, document.getCurrentVersion()));
        if (!content.downloadable()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "필요한 승인이 모두 끝난 결재 문서만 받을 수 있습니다");
        }
        return pdfRenderer.render(content);
    }

    private ApprovalDocumentResponse assemble(Request request, ApprovalDocument document,
                                              ApprovalDocumentVersion version) {
        Plan plan = findPlan(version);
        List<Approval> approvals = approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId());
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다"));

        Set<UUID> userIds = new HashSet<>();
        userIds.add(request.getRequesterId());
        approvals.forEach(approval -> userIds.add(approval.getApproverId()));
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, String> departmentNames = departmentRepository.findAllById(users.values().stream()
                        .map(User::getDepartmentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));

        List<ApprovalSlotResponse> slots = plan.getTasks().stream()
                .map(task -> ApprovalSlotResponse.of(task, stamps(task, approvals, users, departmentNames)))
                .toList();
        List<TaskResultResponse> results = plan.getTasks().stream().anyMatch(task -> EXECUTED.contains(task.getStatus()))
                ? plan.getTasks().stream().map(TaskResultResponse::from).toList()
                : List.of();

        User requester = users.get(request.getRequesterId());
        return ApprovalDocumentResponse.of(document, version, status(request, document, version, plan, approvals),
                request, project, requester, departmentName(requester, departmentNames), plan, slots, results);
    }

    // 작업에 서명한 승인자마다 도장 하나. 같은 승인자가 여러 번 제출했으면 처음 것만 씀 (satisfied와 같은 기준)
    private List<StampResponse> stamps(Task task, List<Approval> approvals, Map<UUID, User> users,
                                       Map<UUID, String> departmentNames) {
        Map<UUID, Approval> firstByApprover = new LinkedHashMap<>();
        for (Approval approval : approvals) {
            if (approval.getTaskIds() != null && approval.getTaskIds().contains(task.getId())) {
                firstByApprover.putIfAbsent(approval.getApproverId(), approval);
            }
        }
        List<StampResponse> stamps = new ArrayList<>();
        for (Approval approval : firstByApprover.values()) {
            User approver = users.get(approval.getApproverId());
            stamps.add(StampResponse.of(approval, approver, departmentName(approver, departmentNames)));
        }
        return stamps;
    }

    private ApprovalDocumentResponse.Status status(Request request, ApprovalDocument document,
                                                   ApprovalDocumentVersion version) {
        if (version.getVersion() != document.getCurrentVersion()) {
            return ApprovalDocumentResponse.Status.SUPERSEDED;
        }
        Plan plan = findPlan(version);
        return status(request, document, version, plan,
                approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId()));
    }

    // 이전 판 → SUPERSEDED, 거부·취소·중단된 요청 → 그 상태, Plan 수정 중 → REVISING,
    // 금지 작업을 뺀 모든 작업이 승인 충족 → APPROVED.
    // 실행이 시작되면 작업 상태가 APPROVED에서 바뀌므로 승인 충족은 상태가 아니라 Approval 기록으로 판정함
    private ApprovalDocumentResponse.Status status(Request request, ApprovalDocument document,
                                                   ApprovalDocumentVersion version, Plan plan,
                                                   List<Approval> approvals) {
        if (version.getVersion() != document.getCurrentVersion()) {
            return ApprovalDocumentResponse.Status.SUPERSEDED;
        }
        switch (request.getStatus()) {
            case REJECTED -> {
                return ApprovalDocumentResponse.Status.REJECTED;
            }
            case CANCELED -> {
                return ApprovalDocumentResponse.Status.CANCELED;
            }
            case KILLED -> {
                return ApprovalDocumentResponse.Status.KILLED;
            }
            default -> {
            }
        }
        if (REVISING.contains(request.getStatus())) {
            return ApprovalDocumentResponse.Status.REVISING;
        }
        boolean approved = plan.getTasks().stream()
                .filter(task -> task.getRisk() != Risk.FORBIDDEN)
                .allMatch(task -> approvalPolicy.satisfied(task, approvals));
        return approved ? ApprovalDocumentResponse.Status.APPROVED : ApprovalDocumentResponse.Status.IN_PROGRESS;
    }

    private String departmentName(User user, Map<UUID, String> departmentNames) {
        return user == null || user.getDepartmentId() == null ? null : departmentNames.get(user.getDepartmentId());
    }

    private void saveVersion(ApprovalDocument document, int version, Plan plan) {
        approvalDocumentVersionRepository.save(ApprovalDocumentVersion.builder()
                .document(document)
                .version(version)
                .plan(plan)
                .build());
    }

    // 아직 제출되지 않았거나 결재 문서 대상이 아닌 요청(에디터 변경·문서 롤백)
    private ApprovalDocument findDocument(Request request) {
        return approvalDocumentRepository.findByRequest_Id(request.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "결재 문서를 찾을 수 없습니다"));
    }

    private ApprovalDocumentVersion findVersion(ApprovalDocument document, int version) {
        return approvalDocumentVersionRepository.findByDocument_IdAndVersion(document.getId(), version)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "결재 문서 판을 찾을 수 없습니다"));
    }

    private Plan findPlan(ApprovalDocumentVersion version) {
        return planRepository.findWithTasksById(version.getPlan().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Plan을 찾을 수 없습니다"));
    }
}
