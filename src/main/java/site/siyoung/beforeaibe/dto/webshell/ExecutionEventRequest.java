package site.siyoung.beforeaibe.dto.webshell;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// 웹쉘 → 웹 서비스 실행 이벤트 콜백 본문 (API 명세서 5 콜백). 필드는 컬렉션 예시 기준이며 계약 확정 전.
// type별 data 규칙은 ExecutionEventService에 있음
public record ExecutionEventRequest(
        @NotNull UUID eventId,
        @NotBlank @Size(max = 50) String type,
        @NotNull UUID executionId,
        @NotNull UUID requestId,
        UUID taskId,
        @NotNull Instant at,
        Map<String, Object> data,
        @NotBlank String webshellSignature
) {
}
