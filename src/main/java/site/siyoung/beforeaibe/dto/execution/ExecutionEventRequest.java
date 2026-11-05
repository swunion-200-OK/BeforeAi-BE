package site.siyoung.beforeaibe.dto.execution;

import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

// 웹쉘 서명 실행 이벤트 (POST /internal/executions/{executionId}/events).
// 서명은 받은 값 그대로를 JCS로 정규화해 확인해야 하므로 ID·시각도 문자열로 받고 형식 검사는 서비스에서 함
public record ExecutionEventRequest(
        @NotBlank String eventId,
        @NotBlank String type,
        @NotBlank String executionId,
        @NotBlank String requestId,
        String taskId,
        @NotBlank String at,
        Map<String, Object> data,
        @NotBlank String webshellSignature
) {

    // 서명 대상: 본문에서 webshellSignature를 뺀 객체. 없는 선택 필드(taskId·data)는 넣지 않음
    public Map<String, Object> signedContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("eventId", eventId);
        content.put("type", type);
        content.put("executionId", executionId);
        content.put("requestId", requestId);
        if (taskId != null) {
            content.put("taskId", taskId);
        }
        content.put("at", at);
        if (data != null) {
            content.put("data", data);
        }
        return content;
    }
}
