package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalCreateRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.ForbiddenService;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/departments/{deptId}")
public class DepartmentForbiddenController {
    private final ForbiddenService service;

    @GetMapping("/forbidden-rules")
    public CursorPageResponse<ForbiddenRuleResponse> rules(@PathVariable UUID deptId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.rules(deptId, cursor, limit);
    }

    @PostMapping("/forbidden-proposals")
    @InternalNetworkOnly
    @ResponseStatus(HttpStatus.CREATED)
    public ForbiddenProposalResponse propose(@AuthenticationPrincipal AuthUser user, @PathVariable UUID deptId,
            @Valid @RequestBody ForbiddenProposalCreateRequest body) {
        return service.propose(user.id(), deptId, body);
    }
}
