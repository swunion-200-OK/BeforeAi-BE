package site.siyoung.beforeaibe.entity.enums;

// 등록된 webhook의 대상 플랫폼. 플랫폼마다 발송 본문(JSON) 스키마가 달라 어댑터로 분기함.
// SLACK을 1순위로 구현·검증하고 DISCORD/GENERIC은 같은 어댑터 구조로 확장함.
public enum WebhookType {
    // Incoming Webhook. 본문 { "text": "..." }
    SLACK,
    // Webhook. 본문 { "content": "..." }
    DISCORD,
    // 범용. 상태 변화 공통 JSON을 그대로 POST
    GENERIC
}
