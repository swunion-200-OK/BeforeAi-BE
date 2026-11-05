package site.siyoung.beforeaibe.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;

// PUT /admin/policy/preset — 규모 프리셋 적용 (ADM-04)
public record ApplyPresetRequest(
        @NotNull PolicyPreset preset,
        @NotNull @Valid AdminProof adminProof
) {
}
