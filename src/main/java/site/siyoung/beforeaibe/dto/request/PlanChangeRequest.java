package site.siyoung.beforeaibe.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Plan 수정 항목. UPDATE는 instruction으로 재분석을 요청하고, DELETE는 작업을 바로 뺌 (API 명세서 4.2)
public record PlanChangeRequest(
        @NotNull Op op,
        @NotNull UUID taskId,
        String instruction
) {

    public enum Op {
        UPDATE,
        DELETE
    }
}
