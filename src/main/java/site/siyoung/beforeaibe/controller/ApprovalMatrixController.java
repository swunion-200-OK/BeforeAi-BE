package site.siyoung.beforeaibe.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.admin.ApprovalMatrixResponse;
import site.siyoung.beforeaibe.service.PolicyService;

// 승인 기준표 조회 (CORE-02). 권한·망 제한 없이 모든 로그인 사용자가 조회 (GET /approval-matrix)
@RestController
@RequestMapping("/api/v1/approval-matrix")
@RequiredArgsConstructor
public class ApprovalMatrixController {

    private final PolicyService policyService;

    @GetMapping
    public ApprovalMatrixResponse getApprovalMatrix() {
        return policyService.getApprovalMatrix();
    }
}
