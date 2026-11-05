package site.siyoung.beforeaibe.dto.me;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SigningKeyCreateRequest(
        @NotBlank String algorithm,
        @NotBlank String publicKey,
        @Size(max = 100) String deviceName
) {
}
