package site.siyoung.beforeaibe.dto.forbidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import java.util.UUID;
public record ForbiddenRuleCreateRequest(@NotNull UUID departmentId, @NotNull TaskType taskType,
        @NotBlank @Size(max = 1024) String target, @NotBlank String reason,
        @NotNull @Valid AdminProof adminProof) {}
