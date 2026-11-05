package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;

// POST /admin/signing-keys/{keyId}/register 응답 (ADM-09)
public record RegisterKeyResponse(UUID keyId, SigningKeyStatus status, int policyVersion) {
}
