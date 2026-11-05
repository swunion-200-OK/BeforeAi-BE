package site.siyoung.beforeaibe.dto.approval;

import site.siyoung.beforeaibe.dto.request.RequesterResponse;
import site.siyoung.beforeaibe.dto.request.TaskResponse;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;

import java.time.Instant;
import java.util.UUID;

// 내 승인 대기 작업 한 건 (3.6 CORE-04). 응답 단위가 요청이 아니라 작업이라 요청·Plan·요청자 정보를 함께 담음
public record PendingApprovalResponse(
        UUID requestId,
        UUID planId,
        RequesterResponse requester,
        Instant requestedAt,
        TaskResponse task
) {

    public static PendingApprovalResponse of(Request request, Plan plan, User requester, Task task) {
        return new PendingApprovalResponse(request.getId(), plan.getId(), RequesterResponse.from(requester),
                request.getCreatedAt(), TaskResponse.from(task));
    }
}
