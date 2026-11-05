package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import site.siyoung.beforeaibe.dto.admin.ApplyPresetRequest;
import site.siyoung.beforeaibe.dto.admin.ApplyPresetResponse;
import site.siyoung.beforeaibe.dto.admin.PolicyResponse;
import site.siyoung.beforeaibe.dto.admin.UpdateApprovalMatrixRequest;
import site.siyoung.beforeaibe.dto.admin.UpdateApprovalMatrixResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.PolicyService;

// 규모 프리셋·승인 기준표 정책 (ADM-03, ADM-04). ADMIN + 내부망
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@InternalNetworkOnly
public class AdminPolicyController {

    private final PolicyService policyService;

    @GetMapping("/policy")
    public PolicyResponse getPolicy() {
        return policyService.getPolicy();
    }

    @PutMapping("/policy/preset")
    public ApplyPresetResponse applyPreset(@AuthenticationPrincipal AuthUser authUser,
                                           @Valid @RequestBody ApplyPresetRequest request) {
        return policyService.applyPreset(authUser.id(), request.preset(), request.adminProof());
    }

    @PutMapping("/approval-matrix")
    public UpdateApprovalMatrixResponse updateApprovalMatrix(@AuthenticationPrincipal AuthUser authUser,
                                                             @Valid @RequestBody UpdateApprovalMatrixRequest request) {
        return policyService.updateApprovalMatrix(authUser.id(), request.rules(), request.adminProof());
    }
}
