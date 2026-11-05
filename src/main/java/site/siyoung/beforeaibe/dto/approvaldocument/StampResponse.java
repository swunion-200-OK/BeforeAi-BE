package site.siyoung.beforeaibe.dto.approvaldocument;

import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.User;

import java.time.Instant;
import java.util.UUID;

// 결재란 도장 하나 = 승인자 한 명의 승인 기록. 망·keyId는 문서에 표시하지 않음
public record StampResponse(
        UUID approverId,
        String approverName,
        String departmentName,
        Instant approvedAt
) {

    // 승인자 계정이 없으면(삭제 등) 이름 대신 ID 앞자리를 보여 줌
    public static StampResponse of(Approval approval, User approver, String departmentName) {
        String name = approver == null ? approval.getApproverId().toString().substring(0, 8) : approver.getName();
        return new StampResponse(approval.getApproverId(), name, departmentName, approval.getCreatedAt());
    }
}
