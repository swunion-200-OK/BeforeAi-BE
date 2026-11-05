package site.siyoung.beforeaibe.dto.admin;

import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;

// PUT /admin/policy/preset 응답 (ADM-04)
public record ApplyPresetResponse(PolicyPreset preset, int policyVersion, boolean webshellApplied) {

    public static ApplyPresetResponse from(PolicyVersion policy) {
        return new ApplyPresetResponse(policy.getPreset(), policy.getVersion(), policy.isWebshellApplied());
    }
}
