package site.siyoung.beforeaibe.dto.admin;

import site.siyoung.beforeaibe.entity.Webhook;
import site.siyoung.beforeaibe.entity.enums.WebhookType;

import java.time.Instant;
import java.util.UUID;

// webhook 조회 응답. url은 비밀값(Incoming Webhook 주소)이라 마스킹해서 노출한다.
public record WebhookResponse(
        UUID id,
        String name,
        WebhookType type,
        String maskedUrl,
        boolean enabled,
        Instant createdAt
) {
    public static WebhookResponse from(Webhook webhook) {
        return new WebhookResponse(
                webhook.getId(),
                webhook.getName(),
                webhook.getType(),
                mask(webhook.getUrl()),
                webhook.isEnabled(),
                webhook.getCreatedAt());
    }

    // 끝 6자만 남기고 가림. 전체 유출 없이 어떤 URL인지 식별만 돕는다.
    private static String mask(String url) {
        if (url == null || url.length() <= 6) {
            return "******";
        }
        return "******" + url.substring(url.length() - 6);
    }
}
