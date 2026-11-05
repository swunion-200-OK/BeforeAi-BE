package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestAcceptResponse;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestCreateRequest;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestRejectRequest;
import site.siyoung.beforeaibe.dto.project.SpecChangeRequestResponse;
import site.siyoung.beforeaibe.dto.project.SpecResponse;
import site.siyoung.beforeaibe.dto.project.SpecRestoreRequest;
import site.siyoung.beforeaibe.dto.project.SpecUpdateRequest;
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.ProjectSpecService;
import site.siyoung.beforeaibe.service.SpecChangeRequestService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/spec")
@RequiredArgsConstructor
public class ProjectSpecController {

    private final ProjectSpecService projectSpecService;
    private final SpecChangeRequestService specChangeRequestService;

    @GetMapping
    public SpecResponse spec(@PathVariable UUID projectId) {
        return projectSpecService.getCurrent(projectId);
    }

    @InternalNetworkOnly
    @PutMapping
    public SpecResponse update(@AuthenticationPrincipal AuthUser authUser,
                               @PathVariable UUID projectId,
                               @Valid @RequestBody SpecUpdateRequest request) {
        return projectSpecService.update(projectId, authUser.id(), request);
    }

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping("/versions")
    public CursorPageResponse<SpecResponse> versions(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return projectSpecService.versions(projectId, cursor, limit);
    }

    @InternalNetworkOnly
    @PostMapping("/versions/{version}/restore")
    public SpecResponse restore(@AuthenticationPrincipal AuthUser authUser,
                                @PathVariable UUID projectId,
                                @PathVariable int version,
                                @RequestBody(required = false) SpecRestoreRequest request) {
        return projectSpecService.restore(projectId, version, authUser.id(), request);
    }

    @InternalNetworkOnly
    @PostMapping("/change-requests")
    public ResponseEntity<SpecChangeRequestResponse> createChangeRequest(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody SpecChangeRequestCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(specChangeRequestService.create(projectId, authUser.id(), request));
    }

    @InternalNetworkOnly
    @GetMapping("/change-requests")
    public CursorPageResponse<SpecChangeRequestResponse> changeRequests(
            @PathVariable UUID projectId,
            @RequestParam(required = false) SpecChangeRequestStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return specChangeRequestService.list(projectId, status, cursor, limit);
    }

    @InternalNetworkOnly
    @PostMapping("/change-requests/{crId}/accept")
    public SpecChangeRequestAcceptResponse acceptChangeRequest(@AuthenticationPrincipal AuthUser authUser,
                                                               @PathVariable UUID projectId,
                                                               @PathVariable UUID crId) {
        return specChangeRequestService.accept(projectId, crId, authUser.id());
    }

    @InternalNetworkOnly
    @PostMapping("/change-requests/{crId}/reject")
    public SpecChangeRequestResponse rejectChangeRequest(@AuthenticationPrincipal AuthUser authUser,
                                                         @PathVariable UUID projectId,
                                                         @PathVariable UUID crId,
                                                         @Valid @RequestBody SpecChangeRequestRejectRequest request) {
        return specChangeRequestService.reject(projectId, crId, authUser.id(), request);
    }
}
