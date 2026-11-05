package site.siyoung.beforeaibe.webhook;

import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.enums.WebhookType;
import site.siyoung.beforeaibe.event.RequestStatusChangedEvent;

import java.util.LinkedHashMap;
import java.util.Map;

// 상태 변화 이벤트를 플랫폼별 webhook 본문(JSON 직렬화 대상 Map)으로 변환한다.
// 민감값은 담지 않는다. requestId/projectId/상태/시각만 사용한다.
@Component
public class WebhookPayloadFactory {

    public Object build(WebhookType type, RequestStatusChangedEvent event) {
        return switch (type) {
            case SLACK -> Map.of("text", message(event));
            case DISCORD -> Map.of("content", message(event));
            case GENERIC -> generic(event);
        };
    }

    // Slack/Discord 공통 사람이 읽는 한 줄 메시지
    private String message(RequestStatusChangedEvent event) {
        String previous = event.previousStatus() == null ? "-" : event.previousStatus().name();
        return "요청 " + event.requestId() + " 상태: " + previous + " → " + event.newStatus().name();
    }

    // 범용 수신자가 파싱할 수 있는 구조화 본문
    private Map<String, Object> generic(RequestStatusChangedEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", event.requestId());
        body.put("projectId", event.projectId());
        body.put("previousStatus", event.previousStatus());
        body.put("status", event.newStatus());
        body.put("at", event.at());
        return body;
    }
}
