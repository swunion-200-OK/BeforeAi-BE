package site.siyoung.beforeaibe.event;

import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.time.Instant;
import java.util.UUID;

// 요청(Request) 상태가 바뀌었을 때 발행하는 도메인 이벤트.
// WebhookDispatcher가 @TransactionalEventListener(AFTER_COMMIT)으로 받아 외부로 알림을 보낸다.
// 민감값(프롬프트 전문·승인 서명·토큰)은 담지 않는다. requestId/projectId/상태/시각만 전달한다.
public record RequestStatusChangedEvent(
        UUID requestId,
        UUID projectId,
        RequestStatus previousStatus,
        RequestStatus newStatus,
        UUID actorId,
        Instant at
) {
}
