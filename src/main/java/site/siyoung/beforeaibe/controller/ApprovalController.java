package site.siyoung.beforeaibe.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.approval.ApprovalContextResponse;
import site.siyoung.beforeaibe.dto.approval.ApprovalSubmitRequest;
import site.siyoung.beforeaibe.dto.approval.ApprovalSubmitResponse;
import site.siyoung.beforeaibe.dto.approval.PendingApprovalResponse;
import site.siyoung.beforeaibe.dto.approval.RejectionRequest;
import site.siyoung.beforeaibe.dto.approval.RejectionResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.security.NetworkResolver;
import site.siyoung.beforeaibe.service.ApprovalService;

import java.util.UUID;

// 승인 (API 명세서 3.6). pending은 /approvals, 나머지는 /requests/{id} 아래라 base를 /api/v1로 둠
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final NetworkResolver networkResolver;

    // 내 승인 대기 작업 목록 (전체 망). 목록 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping("/approvals/pending")
    public CursorPageResponse<PendingApprovalResponse> pending(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return approvalService.pending(authUser.id(), cursor, limit);
    }

    // 승인 전 원문·해시 대상 조회 (전체 망). 외부망이면 내부망 전용 작업을 blockedTaskIds로 알려 줌
    @GetMapping("/requests/{requestId}/approval-context")
    public ApprovalContextResponse approvalContext(@AuthenticationPrincipal AuthUser authUser,
                                                   @PathVariable UUID requestId,
                                                   HttpServletRequest httpRequest) {
        return approvalService.approvalContext(authUser.id(), requestId, networkResolver.resolve(httpRequest));
    }

    // 암호화한 승인 서명 제출 (내부망 전용)
    @InternalNetworkOnly
    @PostMapping("/requests/{requestId}/approvals")
    public ApprovalSubmitResponse approve(@AuthenticationPrincipal AuthUser authUser,
                                          @PathVariable UUID requestId,
                                          @Valid @RequestBody ApprovalSubmitRequest request,
                                          HttpServletRequest httpRequest) {
        return approvalService.approve(authUser.id(), requestId, networkResolver.resolve(httpRequest), request);
    }

    // 작업 거부·문서 미적용 (내부망 전용)
    @InternalNetworkOnly
    @PostMapping("/requests/{requestId}/rejections")
    public RejectionResponse reject(@AuthenticationPrincipal AuthUser authUser,
                                    @PathVariable UUID requestId,
                                    @Valid @RequestBody RejectionRequest request) {
        return approvalService.reject(authUser.id(), requestId, request);
    }
}
