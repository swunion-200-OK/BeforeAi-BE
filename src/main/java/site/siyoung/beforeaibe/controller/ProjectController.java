package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import site.siyoung.beforeaibe.dto.project.ProjectOwnersUpdateRequest;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.project.ProjectResponse;
import site.siyoung.beforeaibe.service.ProjectService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    // 담당자 교체. ADMIN 또는 담당 부서 DEPT_MANAGER, 2명 이상
    @InternalNetworkOnly
    @PutMapping("/{projectId}/owners")
    public ProjectResponse replaceOwners(@AuthenticationPrincipal AuthUser authUser, @PathVariable UUID projectId,
                                         @Valid @RequestBody ProjectOwnersUpdateRequest request) {
        return projectService.replaceOwners(authUser.id(), projectId, request);
    }

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping
    public CursorPageResponse<ProjectResponse> projects(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return projectService.list(cursor, limit);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse project(@PathVariable UUID projectId) {
        return projectService.get(projectId);
    }
}
