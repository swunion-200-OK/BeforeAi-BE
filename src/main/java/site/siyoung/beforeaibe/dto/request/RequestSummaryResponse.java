package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.time.Instant;
import java.util.UUID;

// 요청 목록 항목 (API 명세서 2.1 Request 모델)
public record RequestSummaryResponse(
        UUID id,
        UUID projectId,
        RequesterResponse requester,
        String prompt,
        RequestSource source,
        RequestStatus status,
        int analysisAttempts,
        UUID currentPlanId,
        Instant createdAt,
        Instant updatedAt
) {

    public static RequestSummaryResponse of(Request request, User requester) {
        return new RequestSummaryResponse(request.getId(), request.getProjectId(), RequesterResponse.from(requester),
                request.getPrompt(), request.getSource(), request.getStatus(), request.getAnalysisAttempts(),
                request.getCurrentPlanId(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
