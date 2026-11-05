package site.siyoung.beforeaibe.dto.admin;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// PUT /admin/approval-matrix — 승인 기준표 수정 (ADM-03)
public record UpdateApprovalMatrixRequest(
        @NotEmpty List<ApprovalMatrixRule> rules,
        @NotNull @Valid AdminProof adminProof
) {
}
