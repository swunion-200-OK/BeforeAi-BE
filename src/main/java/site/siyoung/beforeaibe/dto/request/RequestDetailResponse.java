package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 요청 상세: 요청 + 현재 Plan(작업별 상태) + 현재 Plan의 승인 현황 + AI 확인 질문
public record RequestDetailResponse(
        UUID id,
        UUID projectId,
        RequesterResponse requester,
        String prompt,
        RequestSource source,
        RequestStatus status,
        int analysisAttempts,
        UUID currentPlanId,
        Instant createdAt,
        Instant updatedAt,
        PlanResponse currentPlan,
        List<ApprovalResponse> approvals,
        String clarification
) {

    public static RequestDetailResponse of(Request request, User requester, PlanResponse currentPlan,
                                           List<ApprovalResponse> approvals) {
        return new RequestDetailResponse(request.getId(), request.getProjectId(), RequesterResponse.from(requester),
                request.getPrompt(), request.getSource(), request.getStatus(), request.getAnalysisAttempts(),
                request.getCurrentPlanId(), request.getCreatedAt(), request.getUpdatedAt(), currentPlan, approvals,
                request.getClarification());
    }
}
