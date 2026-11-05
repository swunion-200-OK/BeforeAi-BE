package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.exception.*;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.support.PolicyChangeLock;
import site.siyoung.beforeaibe.webshell.PolicyDeploymentClient;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PolicyDeploymentService {
    private final PolicyVersionRepository policies;
    private final PolicyChangeLock lock;
    private final PolicyDeploymentClient client;

    @Transactional
    public boolean deploy(UUID id) {
        lock.acquire();
        var policy = policies.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (policy.isWebshellApplied()) return true;
        var latest = policies.findTopByOrderByVersionDesc().orElseThrow();
        // 새 정책 생성과 배포를 같은 잠금으로 직렬화해 이전 버전으로 되돌아가지 않게 함.
        if (!latest.getId().equals(id)) return false;
        if (policy.getPolicyPayload() == null) throw new ApiException(ErrorCode.INVALID_STATE, "재전송할 정책 스냅샷이 없습니다");
        PolicyDeploymentClient.Result result;
        try {
            result = client.apply(policy.getPolicyPayload(), new AdminProof(policy.getAdminKeyId(), policy.getAdminSignature()), policy.getPolicyHash());
        } catch (ApiException e) {
            throw new ApiException(e.getErrorCode(), "정책은 저장되었으나 웹쉘에 배포하지 못했습니다",
                    java.util.Map.of("policyVersion", policy.getVersion(), "policyId", id, "mutationCommitted", true));
        }
        if (result == null || !result.applied() || result.version() != policy.getVersion()
                || !policy.getPolicyHash().equals(result.policyHash())) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE, "웹쉘 정책 버전·해시·적용 결과가 일치하지 않습니다",
                    java.util.Map.of("policyVersion", policy.getVersion(), "policyId", id, "mutationCommitted", true));
        }
        policy.markWebshellApplied();
        return true;
    }

    @Transactional(readOnly = true)
    public UUID pendingVersion() {
        return policies.findTopByOrderByVersionDesc()
                .filter(p -> !p.isWebshellApplied() && p.getPolicyPayload() != null).map(p -> p.getId()).orElse(null);
    }
}
