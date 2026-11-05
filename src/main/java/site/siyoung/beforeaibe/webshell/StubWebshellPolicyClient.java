package site.siyoung.beforeaibe.webshell;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// 웹쉘 연동 스텁. 실제 mTLS·HPKE 클라이언트가 생기기 전까지 "배포 성공"으로 간주한다.
// 실제 구현(5장)에서 이 빈을 대체한다.
@Slf4j
@Component
public class StubWebshellPolicyClient implements WebshellPolicyClient {

    @Override
    public boolean applyPolicy(int policyVersion, List<ApprovalMatrixRule> approvalMatrix, String policyHash) {
        log.info("[stub] 웹쉘 정책 배포 version={} hash={} rules={}", policyVersion, policyHash,
                approvalMatrix == null ? 0 : approvalMatrix.size());
        return true;
    }

    @Override
    public boolean registerSigningKey(UUID keyId, String publicKey) {
        log.info("[stub] 웹쉘 공개키 등록 keyId={}", keyId);
        return true;
    }

    @Override
    public boolean revokeSigningKey(UUID keyId) {
        log.info("[stub] 웹쉘 공개키 폐기 keyId={}", keyId);
        return true;
    }

    @Override
    public List<UUID> killAll(String reason) {
        log.info("[stub] 웹쉘 전사 실행 중단 reason={}", reason);
        return List.of();
    }
}
