package site.siyoung.beforeaibe.support;

import java.util.UUID;

// 정책 변경 시 첨부하는 관리자 서명(adminProof) 검증 지점.
// 서명 대상·정규화·형식이 명세에 확정되지 않아 지금은 형식 검증(필드 존재)만 하고 통과시킨다.
// 실제 Ed25519 검증은 계약 확정 후 이 구현을 대체한다.
public interface AdminProofVerifier {

    void verify(UUID adminKeyId, String signature);
}
