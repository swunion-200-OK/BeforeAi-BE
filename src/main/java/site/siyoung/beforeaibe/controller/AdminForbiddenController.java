package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalConfirmRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalConfirmResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalRejectRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalStatusResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleCreateRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleReleaseRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleStatusResponse;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.ForbiddenService;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@InternalNetworkOnly
@RequestMapping("/api/v1/admin")
public class AdminForbiddenController {
    private final ForbiddenService service;

    @GetMapping("/forbidden-proposals")
    public CursorPageResponse<ForbiddenProposalResponse> proposals(@RequestParam(required = false) ForbiddenProposalStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.proposals(status, cursor, limit);
    }
    @PostMapping("/forbidden-proposals/{proposalId}/confirm")
    public ForbiddenProposalConfirmResponse confirm(@AuthenticationPrincipal AuthUser user, @PathVariable UUID proposalId,
            @Valid @RequestBody ForbiddenProposalConfirmRequest body) {
        return service.confirm(user.id(), proposalId, body);
    }
    @PostMapping("/forbidden-proposals/{proposalId}/reject")
    public ForbiddenProposalStatusResponse reject(@PathVariable UUID proposalId,
            @Valid @RequestBody ForbiddenProposalRejectRequest body) {
        return service.reject(proposalId, body);
    }
    @PostMapping("/forbidden-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public ForbiddenRuleResponse add(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ForbiddenRuleCreateRequest body) {
        return service.add(user.id(), body);
    }
    @PostMapping("/forbidden-rules/{ruleId}/release")
    public ForbiddenRuleStatusResponse release(@AuthenticationPrincipal AuthUser user, @PathVariable UUID ruleId,
            @Valid @RequestBody ForbiddenRuleReleaseRequest body) {
        return service.release(user.id(), ruleId, body);
    }
}
