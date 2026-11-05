package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 요청 상세: 요청 + 현재 Plan(작업별 상태) + 현재 Plan의 승인 현황 + AI 확인 질문 + 진행 이력
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
        String clarification,
        Decision rejection,
        Decision changeRequest,
        Kill kill,
        List<StatusEvent> events,
        List<String> failedChecks,
        List<UUID> changedTaskIds,
        List<String> removedTaskTitles
) {

    // 거부·수정 요청 기록. by는 사용자 이름
    public record Decision(String by, String reason, Instant at, List<UUID> taskIds) {
    }

    public record Kill(String by, String reason, Instant at) {
    }

    // by가 null이면 시스템(AI 분석, 웹쉘 콜백)이 바꾼 상태
    public record StatusEvent(RequestStatus status, Instant at, String by) {
    }

    public record History(Decision rejection, Decision changeRequest, Kill kill, List<StatusEvent> events,
                          List<String> failedChecks, List<UUID> changedTaskIds, List<String> removedTaskTitles) {
    }

    public static RequestDetailResponse of(Request request, User requester, PlanResponse currentPlan,
                                           List<ApprovalResponse> approvals, History history) {
        return new RequestDetailResponse(request.getId(), request.getProjectId(), RequesterResponse.from(requester),
                request.getPrompt(), request.getSource(), request.getStatus(), request.getAnalysisAttempts(),
                request.getCurrentPlanId(), request.getCreatedAt(), request.getUpdatedAt(), currentPlan, approvals,
                request.getClarification(), history.rejection(), history.changeRequest(), history.kill(),
                history.events(), history.failedChecks(), history.changedTaskIds(), history.removedTaskTitles());
    }
}
