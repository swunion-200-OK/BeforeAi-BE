package site.siyoung.beforeaibe.dto.admin;

import jakarta.validation.Valid;

// DELETE /admin/signing-keys/{keyId} 본문 (ADM-09). 등록된 키를 폐기하면 새 정책 버전이 웹쉘에 배포되므로 관리자 서명이 필요함.
// 등록 대기 키는 본문 없이 폐기할 수 있어 필드 자체는 선택값이고, 필요 여부는 서비스에서 키 상태로 판단함
public record RevokeKeyRequest(@Valid AdminProof adminProof) {
}
