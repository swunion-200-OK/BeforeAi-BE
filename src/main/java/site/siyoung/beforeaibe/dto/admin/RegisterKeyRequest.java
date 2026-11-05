package site.siyoung.beforeaibe.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// POST /admin/signing-keys/{keyId}/register — 공개키 웹쉘 정책 등록 (ADM-09)
public record RegisterKeyRequest(@NotNull @Valid AdminProof adminProof) {
}
