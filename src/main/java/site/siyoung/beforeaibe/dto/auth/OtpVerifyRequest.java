package site.siyoung.beforeaibe.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record OtpVerifyRequest(
        @NotBlank String temporaryToken,
        @NotBlank String otpCode
) {
}
