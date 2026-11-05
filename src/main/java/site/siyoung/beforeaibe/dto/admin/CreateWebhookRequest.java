package site.siyoung.beforeaibe.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import site.siyoung.beforeaibe.entity.enums.WebhookType;

// POST /admin/webhooks — webhook 등록. enabled 생략 시 기본 활성.
public record CreateWebhookRequest(
        @NotBlank String name,
        @NotNull WebhookType type,
        @NotBlank String url,
        Boolean enabled
) {
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
