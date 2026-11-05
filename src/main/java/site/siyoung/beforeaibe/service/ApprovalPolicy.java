package site.siyoung.beforeaibe.service;

import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.List;
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

    // 내부망에서만 승인·실행할 수 있는 작업 판정 (계획서 2.5, Open Question 7.1).
    // 1차 규칙: 배포(DEPLOY)이거나 고위험(HIGH) 이상 작업은 내부망 전용
    public boolean internalOnly(Task task) {
        return task.getType() == TaskType.DEPLOY || task.getRisk() == Risk.HIGH;
    }

    // 작업이 필요한 승인 수를 채웠는지. 1차는 서로 다른 승인자가 이 작업을 서명한 수가 필요 승인 수 합 이상이면 충족
    // (여러 rule/count 조합의 정밀 판정은 Open Question 7.2)
    public boolean satisfied(Task task, List<Approval> approvals) {
        long distinctApprovers = approvals.stream()
                .filter(approval -> approval.getTaskIds() != null && approval.getTaskIds().contains(task.getId()))
                .map(Approval::getApproverId)
                .distinct()
                .count();
        int required = task.getRequiredApprovers().stream().mapToInt(approver -> Math.max(1, approver.count())).sum();
        return distinctApprovers >= required;
    }
}
