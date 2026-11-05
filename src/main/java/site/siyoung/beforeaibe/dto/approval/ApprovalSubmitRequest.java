package site.siyoung.beforeaibe.dto.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

// 암호화한 승인 서명 제출 (3.6 CORE-05). envelope는 웹 서비스가 열어 보지 않고 웹쉘로 전달만 함
public record ApprovalSubmitRequest(
        @NotNull UUID planId,
        @NotBlank String planHash,
        @NotEmpty List<UUID> taskIds,
        @NotNull UUID keyId,
        @NotNull @Valid EnvelopeRequest envelope
) {

    public record EnvelopeRequest(
            @NotBlank String alg,
            @NotBlank String encapsulatedKey,
            @NotBlank String ciphertext
    ) {
    }
}
