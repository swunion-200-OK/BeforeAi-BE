package site.siyoung.beforeaibe.dto.admin;

import java.time.Instant;
import java.util.UUID;

import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;

// GET /admin/signing-keys — 등록 대기·등록된 공개키 목록 (ADM-09)
public record AdminSigningKeyView(
        UUID id,
        String algorithm,
        String publicKey,
        SigningKeyStatus status,
        Instant createdAt,
        UUID userId
) {

    public static AdminSigningKeyView from(SigningKey key) {
        return new AdminSigningKeyView(key.getId(), key.getAlgorithm(), key.getPublicKey(),
                key.getStatus(), key.getCreatedAt(), key.getUser().getId());
    }
}
