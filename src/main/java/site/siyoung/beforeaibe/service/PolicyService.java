package site.siyoung.beforeaibe.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.dto.admin.ApplyPresetResponse;
import site.siyoung.beforeaibe.dto.admin.ApprovalMatrixResponse;
import site.siyoung.beforeaibe.dto.admin.PolicyResponse;
import site.siyoung.beforeaibe.dto.admin.UpdateApprovalMatrixResponse;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.AdminProofVerifier;
import site.siyoung.beforeaibe.support.PolicyHasher;
import site.siyoung.beforeaibe.support.PresetApprovalMatrix;
import site.siyoung.beforeaibe.webshell.WebshellPolicyClient;

// 규모 프리셋·승인 기준표 정책 (ADM-03, ADM-04, CORE-02).
// 현재 정책 = 가장 큰 version. 변경 시 새 버전을 INSERT하고 관리자 서명 후 웹쉘에 배포한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PolicyService {

    private final PolicyVersionRepository policyVersionRepository;
    private final UserRepository userRepository;
    private final AdminProofVerifier adminProofVerifier;
    private final PolicyHasher policyHasher;
    private final WebshellPolicyClient webshellPolicyClient;

    public PolicyResponse getPolicy() {
        return PolicyResponse.from(currentPolicy());
    }

    public ApprovalMatrixResponse getApprovalMatrix() {
        return new ApprovalMatrixResponse(currentPolicy().getApprovalMatrix());
    }

    @Transactional
    public ApplyPresetResponse applyPreset(UUID adminUserId, PolicyPreset preset, AdminProof adminProof) {
        adminProofVerifier.verify(adminProof.keyId(), adminProof.signature());
        List<ApprovalMatrixRule> matrix = PresetApprovalMatrix.of(preset);
        PolicyVersion saved = createNextVersion(adminUserId, preset, matrix, adminProof);
        return ApplyPresetResponse.from(saved);
    }

    @Transactional
    public UpdateApprovalMatrixResponse updateApprovalMatrix(UUID adminUserId,
                                                             List<ApprovalMatrixRule> rules,
                                                             AdminProof adminProof) {
        adminProofVerifier.verify(adminProof.keyId(), adminProof.signature());
        validateRules(rules);
        // 프리셋은 유지하고 승인 기준표만 교체
        PolicyPreset preset = currentPolicy().getPreset();
        PolicyVersion saved = createNextVersion(adminUserId, preset, rules, adminProof);
        return UpdateApprovalMatrixResponse.from(saved);
    }

    private PolicyVersion createNextVersion(UUID adminUserId, PolicyPreset preset,
                                            List<ApprovalMatrixRule> matrix, AdminProof adminProof) {
        int nextVersion = policyVersionRepository.findTopByOrderByVersionDesc()
                .map(PolicyVersion::getVersion)
                .orElse(0) + 1;
        String policyHash = policyHasher.hash(preset, matrix);
        boolean applied = webshellPolicyClient.applyPolicy(nextVersion, matrix, policyHash);

        User admin = userRepository.getReferenceById(adminUserId);
        return policyVersionRepository.save(PolicyVersion.builder()
                .version(nextVersion)
                .preset(preset)
                .approvalMatrix(matrix)
                .policyHash(policyHash)
                .adminKeyId(adminProof.keyId())
                .adminSignature(adminProof.signature())
                .webshellApplied(applied)
                .createdBy(admin)
                .build());
    }

    private void validateRules(List<ApprovalMatrixRule> rules) {
        for (ApprovalMatrixRule rule : rules) {
            if (rule.taskType() == null || rule.risk() == null
                    || rule.requiredApprovers() == null || rule.requiredApprovers().isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "승인 기준표 항목이 올바르지 않습니다");
            }
            rule.requiredApprovers().forEach(approver -> {
                if (approver.rule() == null || approver.count() < 1) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "승인 규칙·인원 수가 올바르지 않습니다");
                }
            });
        }
    }

    private PolicyVersion currentPolicy() {
        return policyVersionRepository.findTopByOrderByVersionDesc()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "설정된 정책이 없습니다"));
    }
}
