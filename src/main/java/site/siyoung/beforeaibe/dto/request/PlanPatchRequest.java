package site.siyoung.beforeaibe.dto.request;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

// changes와 answer(AI 확인 질문에 대한 답)는 함께 보낼 수 있고, 둘 중 하나는 있어야 함
public record PlanPatchRequest(
        UUID basePlanId,
        List<@Valid PlanChangeRequest> changes,
        String answer
) {
}
