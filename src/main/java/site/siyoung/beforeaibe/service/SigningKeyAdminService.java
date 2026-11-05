package site.siyoung.beforeaibe.service;

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
    public RegisterKeyResponse register(UUID keyId, AdminProof adminProof) {
        adminProofVerifier.verify(adminProof.keyId(), adminProof.signature());
        SigningKey key = findKey(keyId);
        if (key.getStatus() != SigningKeyStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "등록 대기 상태의 공개키만 등록할 수 있습니다");
        }

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
        boolean applied = webshellPolicyClient.revokeSigningKey(key.getId());
        if (!applied) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE, "웹쉘에서 공개키를 폐기하지 못했습니다");
        }
        key.revoke();
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
