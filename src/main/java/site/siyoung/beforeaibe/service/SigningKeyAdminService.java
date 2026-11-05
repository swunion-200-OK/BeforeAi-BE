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
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.support.CreatedAtCursor;

// 승인자 공개키의 웹쉘 정책 등록/폐기 (ADM-09). 내부망 + ADMIN에서만 호출됨.
// 키 상태와 새 정책 버전은 SigningKeyCommandService 트랜잭션으로 저장하고, 커밋 뒤 그 버전을 웹쉘에 배포함.
// 배포가 실패하면 502이지만 변경은 이미 저장되어 있으며(details.mutationCommitted) 정책 재전송이 다시 배포함
@Service
@RequiredArgsConstructor
public class SigningKeyAdminService {

    private final SigningKeyRepository signingKeyRepository;
    private final SigningKeyCommandService signingKeyCommandService;
    private final PolicyDeploymentService policyDeploymentService;

    @Transactional(readOnly = true)
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

    public RegisterKeyResponse register(UUID adminUserId, UUID keyId, AdminProof adminProof) {
        SigningKeyCommandService.Change<RegisterKeyResponse> change =
                signingKeyCommandService.register(adminUserId, keyId, adminProof);
        deploy(change.policyId());
        return change.response();
    }

    public void revoke(UUID adminUserId, UUID keyId, AdminProof adminProof) {
        deploy(signingKeyCommandService.revoke(adminUserId, keyId, adminProof));
    }

    private void deploy(UUID policyId) {
        if (policyId != null) {
            policyDeploymentService.deploy(policyId);
        }
    }
}
