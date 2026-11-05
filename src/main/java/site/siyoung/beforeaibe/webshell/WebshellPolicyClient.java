package site.siyoung.beforeaibe.webshell;

import java.util.List;
import java.util.UUID;

import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// 웹쉘(내부망·mTLS) 정책 배포/실행 중단에 대한 포트. 실제 mTLS·HPKE 연동은 5장 범위이므로 여기서는 인터페이스만 정의하고
// 스텁(StubWebshellPolicyClient)으로 대체한다.
public interface WebshellPolicyClient {

    // 프리셋/승인 기준표 등 정책 묶음을 웹쉘에 배포. 성공 여부를 돌려줌 (PolicyVersion.webshellApplied에 반영)
    boolean applyPolicy(int policyVersion, List<ApprovalMatrixRule> approvalMatrix, String policyHash);

    // 승인자 공개키를 웹쉘 정책에 등록
    boolean registerSigningKey(UUID keyId, String publicKey);

    // 승인자 공개키를 웹쉘 정책에서 폐기
    boolean revokeSigningKey(UUID keyId);

    // 전사 실행 일괄 중단. 중단된 웹쉘 실행 ID 목록을 돌려줌
    List<UUID> killAll(String reason);
}
