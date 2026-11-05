package site.siyoung.beforeaibe.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String employeeNumber,
        @NotBlank String password
) {
}
