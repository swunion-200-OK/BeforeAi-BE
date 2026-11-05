package site.siyoung.beforeaibe.dto.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// 담당자 2명 이상 규칙은 409 OWNER_MINIMUM으로 응답해야 하므로 여기서 검사하지 않고 서비스에서 검사
public record ProjectCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull UUID departmentId,
        @NotBlank @Size(max = 512) String repository,
        @NotNull List<@NotNull UUID> ownerIds,
        @NotNull @Valid InitialSpec initialSpec
) {

    public record InitialSpec(@NotBlank String body) {
    }
}
