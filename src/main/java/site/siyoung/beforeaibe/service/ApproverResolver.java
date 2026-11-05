package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.repository.PolicyVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// 현재 정책 기준표로 작업의 필요 승인자와 후보를 정함. 후보는 Plan 생성 시점 값으로 고정됨(나중의 조직 변경을 소급하지 않음)
@Component
@RequiredArgsConstructor
@Slf4j
public class ApproverResolver {

    private final PolicyVersionRepository policyVersionRepository;
    private final UserRepository userRepository;

    public List<RequiredApprover> resolve(TaskType type, Risk risk, Project project, UUID requesterId) {
        if (risk == Risk.FORBIDDEN) {
            return List.of();
        }
        List<RequiredApprover> required = policyVersionRepository.findTopByOrderByVersionDesc()
                .map(PolicyVersion::getApprovalMatrix)
                .orElse(List.of())
                .stream()
                .filter(rule -> rule.taskType() == type && rule.risk() == risk)
                .findFirst()
                .map(ApprovalMatrixRule::requiredApprovers)
                .orElseGet(() -> defaults(risk));
        return required.stream().map(approver -> withCandidates(approver, project, requesterId)).toList();
    }

    // 프리셋에 없는 유형·위험도 조합의 기본값
    private List<RequiredApprover> defaults(Risk risk) {
        ApproverRule rule = switch (risk) {
            case HIGH -> ApproverRule.PROJECT_OWNER;
            case MEDIUM -> ApproverRule.SAME_DEPT_OTHER;
            case LOW, FORBIDDEN -> ApproverRule.ANYONE;
        };
        return List.of(new RequiredApprover(rule, 1, null));
    }

    // ANYONE을 뺀 규칙은 요청자 본인을 후보에서 제외함. 본인 승인은 ANYONE(저위험 기본값)에서만 가능
    private RequiredApprover withCandidates(RequiredApprover approver, Project project, UUID requesterId) {
        if (approver.rule() == ApproverRule.ANYONE) {
            return new RequiredApprover(ApproverRule.ANYONE, approver.count(), null);
        }
        Set<UUID> candidates = new LinkedHashSet<>(approver.candidates() != null && !approver.candidates().isEmpty()
                ? approver.candidates() : byRule(approver.rule(), project));
        candidates.remove(requesterId);
        int needed = Math.max(1, approver.count());
        // 담당자가 요청자 혼자인 경우처럼 후보가 모자라면 영원히 승인할 수 없으므로 관리자에게 넘김
        if (candidates.size() < needed) {
            ids(userRepository.findByRoleAndActiveTrue(Role.ADMIN)).stream()
                    .filter(id -> !Objects.equals(id, requesterId))
                    .forEach(candidates::add);
        }
        if (candidates.size() < needed) {
            log.warn("승인 후보가 부족함: projectId={}, rule={}, needed={}, candidates={}",
                    project.getId(), approver.rule(), needed, candidates.size());
        }
        return new RequiredApprover(approver.rule(), approver.count(), new ArrayList<>(candidates));
    }

    private List<UUID> byRule(ApproverRule rule, Project project) {
        UUID departmentId = project.getDepartment().getId();
        return switch (rule) {
            case SAME_DEPT_OTHER -> ids(userRepository.findByDepartmentIdAndActiveTrue(departmentId));
            case PROJECT_OWNER -> project.getOwners().stream()
                    .map(owner -> owner.getUser())
                    .filter(User::isActive)
                    .map(User::getId)
                    .toList();
            case DEPT_MANAGER -> ids(userRepository.findByDepartmentIdAndRoleAndActiveTrue(departmentId, Role.DEPT_MANAGER));
            case ADMIN -> ids(userRepository.findByRoleAndActiveTrue(Role.ADMIN));
            case ANYONE -> List.of();
        };
    }

    private List<UUID> ids(List<User> users) {
        return users.stream().map(User::getId).toList();
    }
}
