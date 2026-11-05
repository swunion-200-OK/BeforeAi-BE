package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.project.ProjectCreateRequest;
import site.siyoung.beforeaibe.dto.project.ProjectResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.ProjectService;

@RestController
@RequestMapping("/api/v1/admin/projects")
@RequiredArgsConstructor
public class AdminProjectController {

    private final ProjectService projectService;

    @InternalNetworkOnly
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal AuthUser authUser,
                                                  @Valid @RequestBody ProjectCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.create(authUser.id(), request));
    }
}
