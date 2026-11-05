package site.siyoung.beforeaibe.dto.admin;

import java.util.List;

import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// GET /approval-matrix — 승인 기준표 조회 (CORE-02)
public record ApprovalMatrixResponse(List<ApprovalMatrixRule> rules) {
}
