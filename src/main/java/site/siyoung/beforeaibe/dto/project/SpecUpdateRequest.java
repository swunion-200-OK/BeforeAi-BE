package site.siyoung.beforeaibe.dto.project;

import jakarta.validation.constraints.NotBlank;

// reason은 선택 입력
public record SpecUpdateRequest(
        @NotBlank String body,
        String reason
) {
}
