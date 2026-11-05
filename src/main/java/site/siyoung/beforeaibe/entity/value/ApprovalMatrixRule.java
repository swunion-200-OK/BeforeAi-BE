package site.siyoung.beforeaibe.entity.value;

import com.fasterxml.jackson.annotation.JsonInclude;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskType;

import java.util.List;

// 승인 기준표 한 줄: 작업 유형·위험도별 필요한 승인권자 (기능 명세서 5.2)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalMatrixRule(
        TaskType taskType,
        Risk risk,
        List<RequiredApprover> requiredApprovers
) {
}
