package site.siyoung.beforeaibe.dto.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// 승인자의 Plan 수정 요청
public record ChangeRequestCreateRequest(
        @NotNull UUID planId,
        @NotEmpty List<UUID> taskIds,
        @NotBlank @Size(max = 1000) String reason
) {
}
