package site.siyoung.beforeaibe.dto.project;

import jakarta.validation.constraints.NotBlank;

public record SpecChangeRequestCreateRequest(
        @NotBlank String content,
        @NotBlank String reason
) {
}
