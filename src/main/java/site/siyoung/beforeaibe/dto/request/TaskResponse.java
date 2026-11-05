package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;

import java.util.List;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        int order,
        TaskType type,
        Risk risk,
        String title,
        String target,
        TaskContent content,
        List<UUID> relatedDepartments,
        List<RequiredApprover> requiredApprovers,
        TaskStatus status
) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getOrder(), task.getType(), task.getRisk(), task.getTitle(),
                task.getTarget(), task.getContent(), task.getRelatedDepartments(), task.getRequiredApprovers(),
                task.getStatus());
    }
}
