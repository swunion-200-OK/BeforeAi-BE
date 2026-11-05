package site.siyoung.beforeaibe.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.dto.admin.RegisterKeyResponse;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.support.AdminProofVerifier;
import site.siyoung.beforeaibe.support.Ed25519SignatureVerifier;
import site.siyoung.beforeaibe.support.PolicyChangeLock;

// 승인자 공개키 등록/폐기와 새 정책 버전 저장을 한 트랜잭션으로 함 (ADM-09).
// 웹쉘 반영은 정책 묶음(등록된 공개키 포함)으로 하므로, 커밋 뒤 SigningKeyAdminService가 그 버전을 배포함
@Service
@RequiredArgsConstructor
@Transactional
public class SigningKeyCommandService {

    private final SigningKeyRepository signingKeyRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final PolicyRevisionService policyRevisionService;
    private final PolicyChangeLock policyChangeLock;
    private final AdminProofVerifier adminProofVerifier;
    private final Ed25519SignatureVerifier ed25519SignatureVerifier;

    public Change<RegisterKeyResponse> register(UUID adminUserId, UUID keyId, AdminProof adminProof) {
        adminProofVerifier.verify(adminProof.keyId(), adminProof.signature());
        policyChangeLock.acquire();
        SigningKey key = findKey(keyId);
        if (key.getStatus() != SigningKeyStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "등록 대기 상태의 공개키만 등록할 수 있습니다");
        }
        verifyRegisterProof(adminUserId, key, adminProof);

        key.register();
        PolicyVersion policy = createPolicyVersion(adminUserId, adminProof);
        return new Change<>(new RegisterKeyResponse(key.getId(), key.getStatus(),
                policy == null ? 0 : policy.getVersion()), policy == null ? null : policy.getId());
    }

    // 등록 대기(PENDING) 키는 웹쉘 정책에 들어간 적이 없으므로 관리자 서명·정책 변경 없이 폐기함 (기기 교체 시 등록 전 키 정리용)
    // 등록된 키는 관리자 서명된 정책만 웹쉘에 반영되므로(API 명세서 5 POST /v1/policies) adminProof가 필요함
    public UUID revoke(UUID adminUserId, UUID keyId, AdminProof adminProof) {
        policyChangeLock.acquire();
        SigningKey key = findKey(keyId);
        if (key.getStatus() == SigningKeyStatus.REVOKED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "이미 폐기된 공개키입니다");
        }
        boolean registered = key.getStatus() == SigningKeyStatus.REGISTERED;
        if (registered && adminProof == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "등록된 공개키를 폐기하려면 관리자 서명이 필요합니다");
        }
        key.revoke();
        if (!registered) {
            return null;
        }
        PolicyVersion policy = createPolicyVersion(adminUserId, adminProof);
        return policy == null ? null : policy.getId();
    }

    // 정책이 아직 하나도 없으면(규모 프리셋 적용 전) 배포할 정책 묶음이 없으므로 키 상태만 바꿈.
    // 이후 첫 정책 버전을 만들 때 등록된 공개키가 정책 묶음에 함께 들어감
    private PolicyVersion createPolicyVersion(UUID adminUserId, AdminProof adminProof) {
        if (policyVersionRepository.findTopByOrderByVersionDesc().isEmpty()) {
            return null;
        }
        return policyRevisionService.create(adminUserId, null, null, adminProof);
    }

    // 등록 서명 대상(UTF-8): "beforeai:register-signing-key:{등록할 keyId}:{등록할 publicKey}"
    // 서명 키는 요청한 관리자 본인의 REGISTERED 키여야 함. 단, 첫 관리자 키는 서명할 등록 키가 없으므로
    // 관리자 본인의 PENDING 키를 그 키 자신으로 서명한 경우(개인키 보유 증명)에 한해 허용함
    private static String registerProofMessage(SigningKey key) {
        return "beforeai:register-signing-key:" + key.getId() + ":" + key.getPublicKey();
    }

    private void verifyRegisterProof(UUID adminUserId, SigningKey target, AdminProof adminProof) {
        SigningKey proofKey = signingKeyRepository.findById(adminProof.keyId())
                .orElseThrow(() -> new ApiException(ErrorCode.KEY_NOT_REGISTERED, "관리자 서명 키를 찾을 수 없습니다"));
        boolean ownedByAdmin = proofKey.getUser().getId().equals(adminUserId);
        boolean selfProof = proofKey.getId().equals(target.getId());
        if (!ownedByAdmin || (proofKey.getStatus() != SigningKeyStatus.REGISTERED && !selfProof)) {
            throw new ApiException(ErrorCode.KEY_NOT_REGISTERED, "관리자 본인의 등록된 서명 키로 서명해야 합니다");
        }

        byte[] message = registerProofMessage(target).getBytes(StandardCharsets.UTF_8);
        switch (ed25519SignatureVerifier.verify(proofKey.getPublicKey(), message, adminProof.signature())) {
            case VALID -> {
            }
            case MALFORMED -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "관리자 서명 형식이 올바르지 않습니다");
            case INVALID_SIGNATURE -> throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "관리자 서명을 확인할 수 없습니다");
        }
    }

    private SigningKey findKey(UUID keyId) {
        return signingKeyRepository.findById(keyId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "공개키를 찾을 수 없습니다"));
    }

    // 변경 결과와 배포할 정책 버전 id. 배포할 버전이 없으면 policyId는 null
    public record Change<T>(T response, UUID policyId) {
    }
}
