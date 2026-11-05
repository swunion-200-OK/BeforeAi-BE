package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.ai.RequestAnalysisDispatcher;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.request.ApprovalResponse;
import site.siyoung.beforeaibe.dto.request.PlanResponse;
import site.siyoung.beforeaibe.dto.request.RequestCreateRequest;
import site.siyoung.beforeaibe.dto.request.RequestDetailResponse;
import site.siyoung.beforeaibe.dto.request.RequestStatusResponse;
import site.siyoung.beforeaibe.dto.request.RequestSummaryResponse;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 작업 요청 생성·목록·상세·취소 (3.5, EMP-01~03)와 다른 요청 서비스가 함께 쓰는 조회·권한 검사
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestService {

    // 분석이 처음 시작되므로 시도 횟수는 1부터 셈
    private static final int FIRST_ANALYSIS_ATTEMPT = 1;
    private static final Set<RequestStatus> CANCELABLE = EnumSet.of(
            RequestStatus.REVIEWING, RequestStatus.PENDING_APPROVAL,
            RequestStatus.REAPPROVAL_REQUIRED, RequestStatus.VERIFICATION_FAILED);

    private final RequestRepository requestRepository;
    private final PlanRepository planRepository;
    private final ApprovalRepository approvalRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final RequestStreamService requestStreamService;
    private final RequestAnalysisDispatcher analysisDispatcher;

    @Transactional
    public RequestStatusResponse create(UUID userId, RequestCreateRequest request) {
        if (!projectRepository.existsById(request.projectId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "존재하지 않는 프로젝트입니다");
        }
        Request saved = requestRepository.save(Request.builder()
                .projectId(request.projectId())
                .requesterId(userId)
                .prompt(request.prompt())
                .source(RequestSource.PROMPT)
                .status(RequestStatus.ANALYZING)
                .analysisAttempts(FIRST_ANALYSIS_ATTEMPT)
                .build());
        // 커밋 후 비동기 AI 분석 시작. 분석기 비활성(Noop)이면 ANALYSIS_FAILED가 되어 수동 편집으로 진행
        analysisDispatcher.dispatchAfterCommit(saved.getId());
        return RequestStatusResponse.from(saved);
    }

    // 조회 범위: ADMIN은 전체, 그 외는 본인 요청·현재 Plan의 승인 후보인 요청, DEPT_MANAGER는 자기 부서 프로젝트의 요청까지
    public CursorPageResponse<RequestSummaryResponse> list(UUID userId, boolean mine, RequestStatus status,
                                                           UUID projectId, String cursor, int limit) {
        User user = findUser(userId);
        boolean all = user.getRole() == Role.ADMIN;
        List<UUID> candidateRequestIds = all ? List.of() : requestRepository.findIdsByCurrentPlanCandidate(userId);
        UUID departmentId = user.getRole() == Role.DEPT_MANAGER ? user.getDepartmentId() : null;
        UUID requesterId = mine ? userId : null;

        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<Request> requests;
        if (cursor == null || cursor.isBlank()) {
            requests = requestRepository.findPage(all, userId, candidateRequestIds, departmentId, requesterId,
                    status, projectId, fetchLimit);
        } else {
            Cursor decoded = Cursor.decode(cursor);
            requests = requestRepository.findPageAfter(all, userId, candidateRequestIds, departmentId, requesterId,
                    status, projectId, decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = requests.size() > limit;
        List<Request> page = hasNext ? requests.subList(0, limit) : requests;
        String nextCursor = hasNext ? Cursor.of(page.getLast()).encode() : null;

        Map<UUID, User> requesters = usersById(page.stream().map(Request::getRequesterId).collect(Collectors.toSet()));
        List<RequestSummaryResponse> items = page.stream()
                .map(request -> RequestSummaryResponse.of(request, requesters.get(request.getRequesterId())))
                .toList();
        return new CursorPageResponse<>(items, nextCursor);
    }

    public RequestDetailResponse get(UUID userId, UUID requestId) {
        Request request = getViewableRequest(requestId, userId);
        User requester = findUser(request.getRequesterId());
        if (request.getCurrentPlanId() == null) {
            return RequestDetailResponse.of(request, requester, null, List.of());
        }
        Plan plan = getCurrentPlan(request);
        List<ApprovalResponse> approvals = approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId())
                .stream().map(ApprovalResponse::from).toList();
        return RequestDetailResponse.of(request, requester, PlanResponse.from(plan), approvals);
    }

    // 외부망에서도 요청자가 취소할 수 있음. 실행 중(RUNNING)인 요청은 kill API로 중단함
    @Transactional
    public RequestStatusResponse cancel(UUID userId, UUID requestId) {
        Request request = getRequesterRequestForUpdate(requestId, userId);
        if (!CANCELABLE.contains(request.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "현재 상태에서는 요청을 취소할 수 없습니다");
        }
        request.cancel();
        requestStreamService.publishNodeUpdated(request, RequestStreamService.requestNodeId(request.getId()),
                request.getStatus().name());
        return RequestStatusResponse.from(request);
    }

    public Request getViewableRequest(UUID requestId, UUID userId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "요청을 찾을 수 없습니다"));
        checkViewable(request, userId);
        return request;
    }

    // 상태를 바꾸는 쪽(킬 스위치)에서 씀. 요청 행을 잠그므로 호출한 쪽 트랜잭션 안에서만 씀
    @Transactional(propagation = Propagation.MANDATORY)
    public Request getViewableRequestForUpdate(UUID requestId, UUID userId) {
        Request request = findForUpdate(requestId);
        checkViewable(request, userId);
        return request;
    }

    // 요청자만 할 수 있는 변경(분석 재시도·Plan 수정·제출·취소·실패 작업 재시도). 요청 행을 잠금
    @Transactional(propagation = Propagation.MANDATORY)
    public Request getRequesterRequestForUpdate(UUID requestId, UUID userId) {
        Request request = findForUpdate(requestId);
        if (!request.getRequesterId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "요청자만 할 수 있습니다");
        }
        return request;
    }

    // 권한 규칙이 "요청자만"이 아닌 변경(문서 롤백)에서 씀. 권한 검사는 호출한 쪽에서 함. 요청 행을 잠금
    @Transactional(propagation = Propagation.MANDATORY)
    public Request getRequestForUpdate(UUID requestId) {
        return findForUpdate(requestId);
    }

    // 현재 Plan과 작업. 아직 Plan이 없으면(분석 전) 이 동작을 할 수 없음
    public Plan getCurrentPlan(Request request) {
        if (request.getCurrentPlanId() == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "아직 Plan이 없습니다");
        }
        return planRepository.findWithTasksById(request.getCurrentPlanId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Plan을 찾을 수 없습니다"));
    }

    // 요청을 볼 수 있는 사람: ADMIN, 요청자, 프로젝트 담당 부서의 DEPT_MANAGER, 현재 Plan 작업의 승인 후보
    private void checkViewable(Request request, UUID userId) {
        User user = findUser(userId);
        if (user.getRole() == Role.ADMIN || request.getRequesterId().equals(userId)) {
            return;
        }
        if (user.getRole() == Role.DEPT_MANAGER && user.getDepartmentId() != null) {
            Project project = projectRepository.findById(request.getProjectId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다"));
            if (project.getDepartment().getId().equals(user.getDepartmentId())) {
                return;
            }
        }
        if (request.getCurrentPlanId() != null && isCandidate(getCurrentPlan(request), userId)) {
            return;
        }
        throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
    }

    private boolean isCandidate(Plan plan, UUID userId) {
        return plan.getTasks().stream()
                .flatMap(task -> task.getRequiredApprovers().stream())
                .anyMatch(approver -> approver.candidates() != null && approver.candidates().contains(userId));
    }

    private Request findForUpdate(UUID requestId) {
        return requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "요청을 찾을 수 없습니다"));
    }

    private Map<UUID, User> usersById(Set<UUID> userIds) {
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    // 목록 커서: 마지막 항목의 (createdAt, id)를 Base64URL로 감싼 값. 클라이언트는 내용을 해석하지 않고 그대로 돌려보냄
    private record Cursor(Instant createdAt, UUID id) {

        static Cursor of(Request request) {
            return new Cursor(request.getCreatedAt(), request.getId());
        }

        static Cursor decode(String value) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
            }
        }

        String encode() {
            String raw = createdAt + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
