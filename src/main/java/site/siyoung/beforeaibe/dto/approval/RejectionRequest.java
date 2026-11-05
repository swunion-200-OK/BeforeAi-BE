package site.siyoung.beforeaibe.dto.approval;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// 작업 거부·문서 미적용 (3.6 CORE-05)
public record RejectionRequest(
        @NotNull UUID planId,
        @NotEmpty List<UUID> taskIds,
        @Size(max = 1000) String reason
) {
}
