package site.siyoung.beforeaibe.dto.forbidden;
import site.siyoung.beforeaibe.entity.ForbiddenRule;
import site.siyoung.beforeaibe.entity.enums.ForbiddenRuleStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import java.util.UUID;
public record ForbiddenRuleResponse(UUID id, UUID departmentId, TaskType taskType,
        String target, String reason, ForbiddenRuleStatus status) {
    public static ForbiddenRuleResponse from(ForbiddenRule value) {
        return new ForbiddenRuleResponse(value.getId(), value.getDepartment().getId(), value.getTaskType(),
                value.getTarget(), value.getReason(), value.getStatus());
    }
}
