package site.siyoung.beforeaibe.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.dto.admin.AdminSigningKeyView;
import site.siyoung.beforeaibe.dto.admin.RegisterKeyResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.support.AdminProofVerifier;
import site.siyoung.beforeaibe.support.CreatedAtCursor;
import site.siyoung.beforeaibe.support.Ed25519SignatureVerifier;
import site.siyoung.beforeaibe.webshell.WebshellPolicyClient;

// 승인자 공개키의 웹쉘 정책 등록/폐기 (ADM-09). 내부망 + ADMIN에서만 호출됨
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SigningKeyAdminService {

    private final SigningKeyRepository signingKeyRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final AdminProofVerifier adminProofVerifier;
    private final WebshellPolicyClient webshellPolicyClient;
    private final Ed25519SignatureVerifier ed25519SignatureVerifier;

    public CursorPageResponse<AdminSigningKeyView> list(String cursor, int limit) {
        Limit fetchLimit = Limit.of(limit + 1);
        List<SigningKey> keys;
        if (cursor == null || cursor.isBlank()) {
            keys = signingKeyRepository.findByOrderByCreatedAtDescIdDesc(fetchLimit);
        } else {
            CreatedAtCursor decoded = CreatedAtCursor.decode(cursor);
            keys = signingKeyRepository.findAllPageAfter(decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = keys.size() > limit;
        List<SigningKey> page = hasNext ? keys.subList(0, limit) : keys;
        String nextCursor = hasNext
                ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new CursorPageResponse<>(page.stream().map(AdminSigningKeyView::from).toList(), nextCursor);
    }

    @Transactional
    public RegisterKeyResponse register(UUID adminUserId, UUID keyId, AdminProof adminProof) {
        adminProofVerifier.verify(adminProof.keyId(), adminProof.signature());
        SigningKey key = findKey(keyId);
        if (key.getStatus() != SigningKeyStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "등록 대기 상태의 공개키만 등록할 수 있습니다");
        }
        verifyRegisterProof(adminUserId, key, adminProof);

        boolean applied = webshellPolicyClient.registerSigningKey(key.getId(), key.getPublicKey());
        if (!applied) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE, "웹쉘에 공개키를 등록하지 못했습니다");
        }
        key.register();
        return new RegisterKeyResponse(key.getId(), key.getStatus(), currentPolicyVersion());
    }

    @Transactional
    public void revoke(UUID keyId) {
        SigningKey key = findKey(keyId);
        if (key.getStatus() == SigningKeyStatus.REVOKED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "이미 폐기된 공개키입니다");
        }
        // 등록 대기(PENDING) 키는 웹쉘 정책에 들어간 적이 없으므로 웹쉘 호출 없이 폐기함 (기기 교체 시 등록 전 키 정리용)
        if (key.getStatus() == SigningKeyStatus.REGISTERED) {
            boolean applied = webshellPolicyClient.revokeSigningKey(key.getId());
            if (!applied) {
                throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE, "웹쉘에서 공개키를 폐기하지 못했습니다");
            }
        }
        key.revoke();
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

    private int currentPolicyVersion() {
        return policyVersionRepository.findTopByOrderByVersionDesc()
                .map(PolicyVersion::getVersion)
                .orElse(0);
    }

    private SigningKey findKey(UUID keyId) {
        return signingKeyRepository.findById(keyId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "공개키를 찾을 수 없습니다"));
    }
}
