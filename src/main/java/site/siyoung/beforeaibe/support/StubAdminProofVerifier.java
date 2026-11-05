package site.siyoung.beforeaibe.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

// adminProof 검증 스텁. 서명 자료의 형식(키 ID·서명 문자열 존재)만 확인하고 통과시킨다.
// 실제 서명 검증은 계약 확정 후 대체한다.
@Component
public class StubAdminProofVerifier implements AdminProofVerifier {

    @Override
    public void verify(UUID adminKeyId, String signature) {
        if (adminKeyId == null || signature == null || signature.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "관리자 서명(adminProof)이 필요합니다");
        }
    }
}
