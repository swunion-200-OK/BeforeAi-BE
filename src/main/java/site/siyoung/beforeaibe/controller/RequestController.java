package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.document.TaskDocumentResponse;
import site.siyoung.beforeaibe.dto.request.ExecutionLogResponse;
import site.siyoung.beforeaibe.dto.request.KillRequest;
import site.siyoung.beforeaibe.dto.request.KillResponse;
import site.siyoung.beforeaibe.dto.request.PlanPatchRequest;
import site.siyoung.beforeaibe.dto.request.PlanRetryResponse;
import site.siyoung.beforeaibe.dto.request.ProgressResponse;
import site.siyoung.beforeaibe.dto.request.RequestCreateRequest;
import site.siyoung.beforeaibe.dto.request.RequestDetailResponse;
import site.siyoung.beforeaibe.dto.request.RequestStatusResponse;
import site.siyoung.beforeaibe.dto.request.RequestSummaryResponse;
import site.siyoung.beforeaibe.dto.request.RollbackRequest;
import site.siyoung.beforeaibe.dto.request.RollbackResponse;
import site.siyoung.beforeaibe.dto.request.SubmitResponse;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.DocumentService;
import site.siyoung.beforeaibe.service.RequestExecutionService;
import site.siyoung.beforeaibe.service.RequestPlanService;
import site.siyoung.beforeaibe.service.RequestProgressService;
import site.siyoung.beforeaibe.service.RequestRollbackService;
import site.siyoung.beforeaibe.service.RequestService;

import java.util.UUID;

// 작업 요청·Plan·실행 (API 명세서 3.5), 요청의 문서 작업·문서 롤백 (3.7)
@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
public class RequestController {

    private static final String LAST_EVENT_ID = "Last-Event-ID";

    private final RequestService requestService;
    private final RequestPlanService requestPlanService;
    private final RequestExecutionService requestExecutionService;
    private final RequestProgressService requestProgressService;
    private final DocumentService documentService;
    private final RequestRollbackService requestRollbackService;

    // 분석은 비동기라 202로 받고, 결과는 상세 조회로 확인함
    @InternalNetworkOnly
    @PostMapping
    public ResponseEntity<RequestStatusResponse> create(@AuthenticationPrincipal AuthUser authUser,
                                                        @Valid @RequestBody RequestCreateRequest request) {
        return ResponseEntity.accepted().body(requestService.create(authUser.id(), request));
    }

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping
    public CursorPageResponse<RequestSummaryResponse> requests(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return requestService.list(authUser.id(), mine, status, projectId, cursor, limit);
    }

    @GetMapping("/{requestId}")
    public RequestDetailResponse request(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return requestService.get(authUser.id(), requestId);
    }

    @InternalNetworkOnly
    @PostMapping("/{requestId}/analysis/retry")
    public ResponseEntity<RequestStatusResponse> retryAnalysis(@AuthenticationPrincipal AuthUser authUser,
                                                               @PathVariable UUID requestId) {
        return ResponseEntity.accepted().body(requestPlanService.retryAnalysis(authUser.id(), requestId));
    }

    @InternalNetworkOnly
    @PatchMapping("/{requestId}/plan")
    public ResponseEntity<RequestStatusResponse> patchPlan(@AuthenticationPrincipal AuthUser authUser,
                                                           @PathVariable UUID requestId,
                                                           @Valid @RequestBody PlanPatchRequest request) {
        return ResponseEntity.accepted().body(requestPlanService.patchPlan(authUser.id(), requestId, request));
    }

    @InternalNetworkOnly
    @PostMapping("/{requestId}/submit")
    public SubmitResponse submit(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return requestPlanService.submit(authUser.id(), requestId);
    }

    // 외부망에서도 취소할 수 있음
    @PostMapping("/{requestId}/cancel")
    public RequestStatusResponse cancel(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return requestService.cancel(authUser.id(), requestId);
    }

    // 킬 스위치는 외부망에서도 허용
    @PostMapping("/{requestId}/kill")
    public KillResponse kill(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId,
                             @RequestBody(required = false) KillRequest request) {
        return requestExecutionService.kill(authUser.id(), requestId, request);
    }

    @InternalNetworkOnly
    @PostMapping("/{requestId}/retry")
    public PlanRetryResponse retry(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return requestPlanService.retryFailedTasks(authUser.id(), requestId);
    }

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping("/{requestId}/logs")
    public CursorPageResponse<ExecutionLogResponse> logs(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable UUID requestId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return requestExecutionService.logs(authUser.id(), requestId, cursor, limit);
    }

    @GetMapping(path = "/{requestId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logStream(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return requestExecutionService.subscribeLogs(authUser.id(), requestId);
    }

    @GetMapping("/{requestId}/progress")
    public ProgressResponse progress(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId) {
        return requestProgressService.getProgress(authUser.id(), requestId);
    }

    // 재연결할 때 Last-Event-ID에 마지막으로 받은 version을 보냄
    @GetMapping(path = "/{requestId}/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progressStream(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId,
                                     @RequestHeader(name = LAST_EVENT_ID, required = false) Long lastEventId) {
        return requestProgressService.subscribeProgress(authUser.id(), requestId, lastEventId);
    }

    // 문서를 적용하지 않음. 적용·미적용은 approvals/rejections로 함
    @GetMapping("/{requestId}/tasks/{taskId}/document")
    public TaskDocumentResponse taskDocument(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId,
                                             @PathVariable UUID taskId) {
        return documentService.taskDocument(authUser.id(), requestId, taskId);
    }

    // 롤백 Plan과 해시만 만듦. 문서 복원은 새 승인 서명이 모인 뒤 웹쉘이 실행함
    @InternalNetworkOnly
    @PostMapping("/{requestId}/rollback")
    public RollbackResponse rollback(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID requestId,
                                     @Valid @RequestBody RollbackRequest request) {
        return requestRollbackService.rollback(authUser.id(), requestId, request);
    }
}
