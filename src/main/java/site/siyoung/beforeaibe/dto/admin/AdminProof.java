package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// 정책 변경 시 첨부하는 관리자 서명 자료. 서명 대상·형식은 명세 미정이라 검증은 AdminProofVerifier에 위임
public record AdminProof(@NotNull UUID keyId, String signature) {
}
