package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

// 문서 롤백 Plan 생성 응답. 이전 승인 서명은 쓸 수 없고 이 planHash에 새로 서명해야 함
public record RollbackResponse(
        UUID requestId,
        RequestSource source,
        RequestStatus status,
        RollbackPlan plan,
        String planHash
) {

    public static RollbackResponse of(Request request, Plan plan) {
        return new RollbackResponse(request.getId(), request.getSource(), request.getStatus(),
                new RollbackPlan(plan.getId(), plan.getKind()), plan.getPlanHash());
    }

    public record RollbackPlan(UUID id, PlanKind kind) {
    }
}
