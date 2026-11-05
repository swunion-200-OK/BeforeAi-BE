package site.siyoung.beforeaibe.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.dashboard.DashboardResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.service.DashboardService;

import java.util.UUID;

// 역할별 대시보드 (API 명세서 3.9). 망 전체에서 사용 가능
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    // scope를 생략하면 역할 기본값(ADMIN→COMPANY, DEPT_MANAGER→DEPARTMENT, EMPLOYEE→ME)
    @GetMapping
    public DashboardResponse dashboard(@AuthenticationPrincipal AuthUser authUser,
                                       @RequestParam(required = false) DashboardResponse.Scope scope,
                                       @RequestParam(required = false) UUID departmentId) {
        return dashboardService.get(authUser.id(), scope, departmentId);
    }
}
