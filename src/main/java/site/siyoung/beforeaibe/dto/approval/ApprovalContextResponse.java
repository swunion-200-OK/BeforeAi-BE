package site.siyoung.beforeaibe.dto.approval;

import site.siyoung.beforeaibe.dto.request.PlanResponse;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// 승인 전 원문·해시 대상 조회 (3.6 CORE-04).
// hashTarget·planHash는 PlanHashService로 만들어 승인자 기기·웹쉘과 같은 규칙을 보장함.
// blockedReason은 외부망에서 서명할 수 없는 작업이 있을 때 INTERNAL_NETWORK_ONLY
public record ApprovalContextResponse(
        UUID requestId,
        PlanResponse plan,
        Map<String, Object> hashTarget,
        String planHash,
        List<UUID> approvableTaskIds,
        List<UUID> blockedTaskIds,
        String blockedReason,
        Network network
) {
}
