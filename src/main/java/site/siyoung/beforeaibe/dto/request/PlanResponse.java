package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.enums.PlanKind;

import java.util.List;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        UUID requestId,
        PlanKind kind,
        String repository,
        String baseCommit,
        String planHash,
        List<TaskResponse> tasks
) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(plan.getId(), plan.getRequest().getId(), plan.getKind(), plan.getRepository(),
                plan.getBaseCommit(), plan.getPlanHash(), plan.getTasks().stream().map(TaskResponse::from).toList());
    }
}
