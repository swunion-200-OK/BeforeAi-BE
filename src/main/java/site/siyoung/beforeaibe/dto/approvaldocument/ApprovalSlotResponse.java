package site.siyoung.beforeaibe.dto.approvaldocument;

import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;

import java.util.List;
import java.util.UUID;

// 결재란 칸 하나 = 작업 하나. 칸 안의 도장 자리 수는 필요 승인 수 합(ApprovalPolicy.satisfied와 같은 기준)
public record ApprovalSlotResponse(
        UUID taskId,
        int order,
        String title,
        TaskType type,
        Risk risk,
        String target,
        List<RequiredApprover> requiredApprovers,
        int requiredCount,
        List<StampResponse> stamps
) {

    public static ApprovalSlotResponse of(Task task, List<StampResponse> stamps) {
        int requiredCount = task.getRequiredApprovers().stream()
                .mapToInt(approver -> Math.max(1, approver.count()))
                .sum();
        return new ApprovalSlotResponse(task.getId(), task.getOrder(), task.getTitle(), task.getType(),
                task.getRisk(), task.getTarget(), task.getRequiredApprovers(), requiredCount, stamps);
    }

    // 금지 작업은 결재·실행 대상이 아니라 칸에 도장 자리를 두지 않음
    public boolean forbidden() {
        return risk == Risk.FORBIDDEN;
    }
}
