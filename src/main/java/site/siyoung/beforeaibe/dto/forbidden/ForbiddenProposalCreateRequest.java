package site.siyoung.beforeaibe.dto.forbidden;
import jakarta.validation.constraints.*;
import site.siyoung.beforeaibe.entity.enums.TaskType;
public record ForbiddenProposalCreateRequest(@NotNull TaskType taskType,
        @NotBlank @Size(max = 1024) String target, @NotBlank String reason) {}
