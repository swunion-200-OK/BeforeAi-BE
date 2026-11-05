package site.siyoung.beforeaibe.dto.me;

import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;

import java.time.Instant;
import java.util.UUID;

public record SigningKeyResponse(
        UUID id,
        String algorithm,
        String publicKey,
        SigningKeyStatus status,
        Instant createdAt
) {

    public static SigningKeyResponse from(SigningKey signingKey) {
        return new SigningKeyResponse(signingKey.getId(), signingKey.getAlgorithm(), signingKey.getPublicKey(),
                signingKey.getStatus(), signingKey.getCreatedAt());
    }
}
