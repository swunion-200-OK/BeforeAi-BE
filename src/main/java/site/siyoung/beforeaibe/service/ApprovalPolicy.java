package site.siyoung.beforeaibe.service;

import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 승인권자·내부망 요구·승인 충족 판정 규칙을 한곳에 모음. 규칙 확정 시 이 클래스만 바꾸면 됨(계획서 Open Question 7.1·7.2)
@Component
public class ApprovalPolicy {

    // 승인권자 판정: 필요 승인권자 중 ANYONE이 있거나 후보 목록에 사용자가 들어 있음 (3.5와 같은 규칙)
    public boolean isApprover(Task task, UUID userId) {
        return task.getRequiredApprovers().stream().anyMatch(approver ->
                approver.rule() == ApproverRule.ANYONE
                        || (approver.candidates() != null && approver.candidates().contains(userId)));
    }

    // ANYONE이 아닌 규칙은 후보 수가 필요 승인 수 이상이어야 승인이 끝날 수 있음
    public boolean hasEnoughCandidates(Task task) {
        return task.getRequiredApprovers().stream().allMatch(approver -> approver.rule() == ApproverRule.ANYONE
                || (approver.candidates() != null && approver.candidates().size() >= Math.max(1, approver.count())));
    }

    // 내부망에서만 승인·실행할 수 있는 작업 판정 (계획서 2.5, Open Question 7.1).
    // 1차 규칙: 배포(DEPLOY)이거나 고위험(HIGH) 이상 작업은 내부망 전용
    public boolean internalOnly(Task task) {
        return task.getType() == TaskType.DEPLOY || task.getRisk() == Risk.HIGH;
    }

    // 작업이 필요한 승인을 채웠는지. 규칙별 칸(rule × count)마다 서로 다른 승인자를 하나씩 배정할 수 있어야 충족
    // 예: DEPT_MANAGER 1 + PROJECT_OWNER 1이면 담당자 두 명의 승인만으로는 충족되지 않음
    public boolean satisfied(Task task, List<Approval> approvals) {
        List<UUID> approvers = approvals.stream()
                .filter(approval -> approval.getTaskIds() != null && approval.getTaskIds().contains(task.getId()))
                .map(Approval::getApproverId)
                .distinct()
                .toList();
        List<RequiredApprover> slots = new ArrayList<>();
        for (RequiredApprover approver : task.getRequiredApprovers()) {
            for (int i = 0; i < Math.max(1, approver.count()); i++) {
                slots.add(approver);
            }
        }
        if (slots.size() > approvers.size()) {
            return false;
        }
        // 이분 매칭: 승인자 위치 → 배정된 칸
        Map<UUID, Integer> assigned = new HashMap<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            if (!assign(slot, slots, approvers, assigned, new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    private boolean assign(int slot, List<RequiredApprover> slots, List<UUID> approvers, Map<UUID, Integer> assigned,
                           Set<UUID> visited) {
        RequiredApprover required = slots.get(slot);
        for (UUID approver : approvers) {
            boolean eligible = required.rule() == ApproverRule.ANYONE
                    || (required.candidates() != null && required.candidates().contains(approver));
            if (!eligible || !visited.add(approver)) {
                continue;
            }
            Integer current = assigned.get(approver);
            if (current == null || assign(current, slots, approvers, assigned, visited)) {
                assigned.put(approver, slot);
                return true;
            }
        }
        return false;
    }
}
