package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy();
    private final UUID manager = UUID.randomUUID();
    private final UUID owner1 = UUID.randomUUID();
    private final UUID owner2 = UUID.randomUUID();

    private Task managerAndOwnerTask() {
        Task task = Task.builder().requiredApprovers(List.of(
                new RequiredApprover(ApproverRule.DEPT_MANAGER, 1, List.of(manager)),
                new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, List.of(owner1, owner2)))).build();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        return task;
    }

    private Approval approval(Task task, UUID approver) {
        return Approval.builder().approverId(approver).taskIds(List.of(task.getId())).build();
    }

    @Test
    void 규칙별로_서로_다른_승인자가_모이면_충족된다() {
        Task task = managerAndOwnerTask();
        assertThat(policy.satisfied(task, List.of(approval(task, manager), approval(task, owner1)))).isTrue();
    }

    @Test
    void 같은_규칙의_승인자만_모이면_수가_맞아도_충족되지_않는다() {
        Task task = managerAndOwnerTask();
        assertThat(policy.satisfied(task, List.of(approval(task, owner1), approval(task, owner2)))).isFalse();
    }

    @Test
    void 한_사람이_두_칸을_채울_수_없다() {
        UUID both = UUID.randomUUID();
        Task task = Task.builder().requiredApprovers(List.of(
                new RequiredApprover(ApproverRule.DEPT_MANAGER, 1, List.of(both)),
                new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, List.of(both)))).build();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        assertThat(policy.satisfied(task, List.of(approval(task, both)))).isFalse();
    }

    @Test
    void 두_규칙에_모두_해당하는_승인자는_남는_칸으로_배정된다() {
        UUID both = UUID.randomUUID();
        Task task = Task.builder().requiredApprovers(List.of(
                new RequiredApprover(ApproverRule.DEPT_MANAGER, 1, List.of(both)),
                new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, List.of(both, owner1)))).build();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        assertThat(policy.satisfied(task, List.of(approval(task, owner1), approval(task, both)))).isTrue();
    }
}
