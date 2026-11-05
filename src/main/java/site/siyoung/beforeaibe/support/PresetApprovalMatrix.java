package site.siyoung.beforeaibe.support;

import java.util.List;

import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;

// 규모 프리셋(SMALL/MEDIUM/LARGE)별 기본 승인 기준표 (ADM-04).
// 아래 값은 명세에 확정되지 않은 잠정값으로, 운영 정책이 정해지면 교체한다.
public final class PresetApprovalMatrix {

    private PresetApprovalMatrix() {
    }

    public static List<ApprovalMatrixRule> of(PolicyPreset preset) {
        return switch (preset) {
            case SMALL -> small();
            case MEDIUM -> medium();
            case LARGE -> large();
        };
    }

    private static List<ApprovalMatrixRule> small() {
        return List.of(
                rule(TaskType.CODE_FEATURE, Risk.MEDIUM, approver(ApproverRule.ANYONE, 1)),
                rule(TaskType.DEPLOY, Risk.HIGH, approver(ApproverRule.PROJECT_OWNER, 1)));
    }

    private static List<ApprovalMatrixRule> medium() {
        return List.of(
                rule(TaskType.CODE_FEATURE, Risk.MEDIUM, approver(ApproverRule.SAME_DEPT_OTHER, 1)),
                rule(TaskType.DEPLOY, Risk.HIGH, approver(ApproverRule.PROJECT_OWNER, 1)));
    }

    private static List<ApprovalMatrixRule> large() {
        return List.of(
                rule(TaskType.CODE_FEATURE, Risk.MEDIUM, approver(ApproverRule.SAME_DEPT_OTHER, 1)),
                rule(TaskType.DEPLOY, Risk.HIGH, approver(ApproverRule.DEPT_MANAGER, 1), approver(ApproverRule.PROJECT_OWNER, 1)));
    }

    private static ApprovalMatrixRule rule(TaskType taskType, Risk risk, RequiredApprover... approvers) {
        return new ApprovalMatrixRule(taskType, risk, List.of(approvers));
    }

    private static RequiredApprover approver(ApproverRule rule, int count) {
        return new RequiredApprover(rule, count, null);
    }
}
