package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 요청 상세의 승인 현황. 승인 봉투(envelope)는 웹쉘만 여는 값이라 내려 주지 않음
public record ApprovalResponse(
        UUID id,
        UUID planId,
        UUID approverId,
        List<UUID> taskIds,
        Network network,
        Instant createdAt
) {

    public static ApprovalResponse from(Approval approval) {
        return new ApprovalResponse(approval.getId(), approval.getPlan().getId(), approval.getApproverId(),
                approval.getTaskIds(), approval.getNetwork(), approval.getCreatedAt());
    }
}
