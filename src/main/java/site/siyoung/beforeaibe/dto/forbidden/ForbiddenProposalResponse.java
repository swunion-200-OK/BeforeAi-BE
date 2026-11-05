package site.siyoung.beforeaibe.dto.forbidden;
import site.siyoung.beforeaibe.entity.ForbiddenProposal;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import java.util.UUID;
public record ForbiddenProposalResponse(UUID id, UUID departmentId, TaskType taskType,
        String target, String reason, ForbiddenProposalStatus status) {
    public static ForbiddenProposalResponse from(ForbiddenProposal value) {
        return new ForbiddenProposalResponse(value.getId(), value.getDepartment().getId(), value.getTaskType(),
                value.getTarget(), value.getReason(), value.getStatus());
    }
}
