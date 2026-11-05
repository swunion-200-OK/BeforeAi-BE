package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalCreateRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalConfirmRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalConfirmResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalRejectRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenProposalStatusResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleCreateRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleReleaseRequest;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleResponse;
import site.siyoung.beforeaibe.dto.forbidden.ForbiddenRuleStatusResponse;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.ForbiddenProposal;
import site.siyoung.beforeaibe.entity.ForbiddenRule;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import site.siyoung.beforeaibe.entity.enums.ForbiddenRuleStatus;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ForbiddenProposalRepository;
import site.siyoung.beforeaibe.repository.ForbiddenRuleRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.PolicyChangeLock;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class ForbiddenCommandService {
    private final ForbiddenProposalRepository proposals;
    private final ForbiddenRuleRepository rules;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final PolicyRevisionService revisions;
    private final PolicyChangeLock policyLock;

    public ForbiddenProposalResponse propose(UUID userId, UUID departmentId, ForbiddenProposalCreateRequest body) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (user.getRole() != Role.DEPT_MANAGER || !departmentId.equals(user.getDepartmentId()))
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "소속 부서장만 금지 작업을 제안할 수 있습니다");
        return ForbiddenProposalResponse.from(proposals.saveAndFlush(ForbiddenProposal.builder()
                .department(department(departmentId)).taskType(body.taskType()).target(body.target()).reason(body.reason())
                .status(ForbiddenProposalStatus.OPEN).build()));
    }

    public Change<ForbiddenProposalConfirmResponse> confirm(UUID adminId, UUID id, ForbiddenProposalConfirmRequest body) {
        policyLock.acquire();
        ForbiddenProposal proposal = proposals.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        proposal.confirm();
        ForbiddenRule rule = rules.saveAndFlush(ForbiddenRule.builder().department(proposal.getDepartment())
                .taskType(proposal.getTaskType()).target(proposal.getTarget()).reason(proposal.getReason())
                .status(ForbiddenRuleStatus.CONFIRMED).build());
        PolicyVersion policy = revisions.create(adminId, null, null, body.adminProof());
        return new Change<>(new ForbiddenProposalConfirmResponse(id, proposal.getStatus(), ForbiddenRuleResponse.from(rule),
                policy.getVersion()), policy.getId());
    }

    public ForbiddenProposalStatusResponse reject(UUID id, ForbiddenProposalRejectRequest body) {
        ForbiddenProposal proposal = proposals.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        proposal.reject(body.reason());
        return new ForbiddenProposalStatusResponse(id, proposal.getStatus());
    }

    public Change<ForbiddenRuleResponse> add(UUID adminId, ForbiddenRuleCreateRequest body) {
        policyLock.acquire();
        ForbiddenRule rule = rules.saveAndFlush(ForbiddenRule.builder().department(department(body.departmentId()))
                .taskType(body.taskType()).target(body.target()).reason(body.reason()).status(ForbiddenRuleStatus.CONFIRMED).build());
        PolicyVersion policy = revisions.create(adminId, null, null, body.adminProof());
        return new Change<>(ForbiddenRuleResponse.from(rule), policy.getId());
    }

    public Change<ForbiddenRuleStatusResponse> release(UUID adminId, UUID id, ForbiddenRuleReleaseRequest body) {
        policyLock.acquire();
        ForbiddenRule rule = rules.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        rule.requestRelease(body.reason());
        rules.flush();
        UUID policyId = body.adminProof() == null ? null : revisions.create(adminId, null, null, body.adminProof()).getId();
        return new Change<>(new ForbiddenRuleStatusResponse(id, rule.getStatus()), policyId);
    }

    private Department department(UUID id) {
        return departments.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다"));
    }
    public record Change<T>(T response, UUID policyId) {}
}
