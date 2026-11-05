package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import site.siyoung.beforeaibe.dto.admin.ApplyPresetResponse;
import site.siyoung.beforeaibe.dto.admin.ApprovalMatrixResponse;
import site.siyoung.beforeaibe.dto.admin.PolicyResponse;
import site.siyoung.beforeaibe.dto.admin.UpdateApprovalMatrixResponse;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.support.PresetApprovalMatrix;
import java.util.*;

// 버전 저장 트랜잭션이 끝난 뒤 배포하므로 연결 실패 시에도 재전송할 스냅샷이 남음.
@Service
@RequiredArgsConstructor
public class PolicyService {
    private final PolicyVersionRepository policies;
    private final PolicyRevisionService revisions;
    private final PolicyDeploymentService deployments;

    @Transactional(readOnly = true)
    public PolicyResponse getPolicy() { return PolicyResponse.from(currentPolicy()); }
    @Transactional(readOnly = true)
    public ApprovalMatrixResponse getApprovalMatrix() { return new ApprovalMatrixResponse(currentPolicy().getApprovalMatrix()); }

    public ApplyPresetResponse applyPreset(UUID adminId, PolicyPreset preset, AdminProof proof) {
        PolicyVersion saved = revisions.create(adminId, preset, PresetApprovalMatrix.of(preset), proof);
        boolean applied = deployments.deploy(saved.getId());
        return new ApplyPresetResponse(saved.getPreset(), saved.getVersion(), applied);
    }
    public UpdateApprovalMatrixResponse updateApprovalMatrix(UUID adminId, List<ApprovalMatrixRule> rules, AdminProof proof) {
        PolicyVersion saved = revisions.create(adminId, null, rules, proof);
        boolean applied = deployments.deploy(saved.getId());
        return new UpdateApprovalMatrixResponse(saved.getVersion(), applied, saved.getApprovalMatrix());
    }
    private PolicyVersion currentPolicy() {
        return policies.findTopByOrderByVersionDesc().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "설정된 정책이 없습니다"));
    }
}
