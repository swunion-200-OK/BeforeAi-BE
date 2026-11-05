package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.AdminProofVerifier;
import site.siyoung.beforeaibe.support.PolicyChangeLock;
import site.siyoung.beforeaibe.support.PolicyHasher;
import site.siyoung.beforeaibe.support.PolicyPayloadFactory;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class PolicyRevisionService {
    private final PolicyVersionRepository policies;
    private final UserRepository users;
    private final PolicyChangeLock lock;
    private final AdminProofVerifier proofVerifier;
    private final PolicyPayloadFactory payloadFactory;
    private final PolicyHasher hasher;

    public PolicyVersion create(UUID adminId, PolicyPreset preset, List<ApprovalMatrixRule> matrix, AdminProof proof) {
        lock.acquire();
        if (proof == null) throw new ApiException(ErrorCode.VALIDATION_ERROR, "관리자 서명이 필요합니다");
        proofVerifier.verify(proof.keyId(), proof.signature());
        PolicyVersion current = policies.findTopByOrderByVersionDesc().orElse(null);
        if (preset == null) {
            if (current == null) throw new ApiException(ErrorCode.NOT_FOUND, "먼저 규모 프리셋을 설정해야 합니다");
            preset = current.getPreset();
        }
        if (matrix == null) {
            if (current == null) throw new ApiException(ErrorCode.NOT_FOUND, "설정된 정책이 없습니다");
            matrix = current.getApprovalMatrix();
        }
        validateMatrix(matrix);
        int version = current == null ? 1 : current.getVersion() + 1;
        Map<String, Object> payload = payloadFactory.create(version, preset, matrix);
        PolicyVersion policy = PolicyVersion.builder().version(version).preset(preset).approvalMatrix(matrix)
                .policyHash(hasher.hash(payload)).adminKeyId(proof.keyId()).adminSignature(proof.signature())
                .createdBy(users.getReferenceById(adminId)).webshellApplied(false).build();
        policy.attachPayload(payload);
        return policies.saveAndFlush(policy);
    }

    private void validateMatrix(List<ApprovalMatrixRule> matrix) {
        if (matrix.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        for (ApprovalMatrixRule rule : matrix) {
            if (rule == null || rule.taskType() == null || rule.risk() == null || rule.requiredApprovers() == null
                    || rule.requiredApprovers().isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            for (var approver : rule.requiredApprovers()) {
                if (approver == null || approver.rule() == null || approver.count() < 1)
                    throw new ApiException(ErrorCode.VALIDATION_ERROR);
            }
        }
    }
}
