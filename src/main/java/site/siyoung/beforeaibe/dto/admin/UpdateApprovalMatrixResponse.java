package site.siyoung.beforeaibe.dto.admin;

import java.util.List;

import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// PUT /admin/approval-matrix 응답 (ADM-03)
public record UpdateApprovalMatrixResponse(
        int policyVersion,
        boolean webshellApplied,
        List<ApprovalMatrixRule> rules
) {

    public static UpdateApprovalMatrixResponse from(PolicyVersion policy) {
        return new UpdateApprovalMatrixResponse(policy.getVersion(), policy.isWebshellApplied(),
                policy.getApprovalMatrix());
    }
}
