package site.siyoung.beforeaibe.dto.admin;

import java.util.List;

import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// GET /admin/policy — 현재 규모 프리셋·정책 값 (ADM-04)
public record PolicyResponse(
        PolicyPreset preset,
        int version,
        List<ApprovalMatrixRule> approvalMatrix,
        String policyHash
) {

    public static PolicyResponse from(PolicyVersion policy) {
        return new PolicyResponse(policy.getPreset(), policy.getVersion(),
                policy.getApprovalMatrix(), policy.getPolicyHash());
    }
}
