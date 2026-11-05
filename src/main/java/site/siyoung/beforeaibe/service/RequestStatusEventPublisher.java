package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.event.RequestStatusChangedEvent;

import java.time.Instant;
import java.util.UUID;

// 요청 상태 전이 직후 RequestStatusChangedEvent를 발행하는 얇은 헬퍼.
// 상태 전이 서비스는 엔티티 메서드 호출 뒤 이 메서드만 부르면 된다.
// 발행은 트랜잭션 안에서 하고, 실제 발송은 WebhookDispatcher가 커밋 후 비동기로 한다.
@Component
@RequiredArgsConstructor
public class RequestStatusEventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publish(Request request, RequestStatus previousStatus, UUID actorId) {
        if (previousStatus == request.getStatus()) {
            return;
        }
        publisher.publishEvent(new RequestStatusChangedEvent(
                request.getId(),
                request.getProjectId(),
                previousStatus,
                request.getStatus(),
                actorId,
                Instant.now()));
    }
}
