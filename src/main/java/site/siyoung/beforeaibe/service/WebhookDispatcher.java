package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import site.siyoung.beforeaibe.entity.Webhook;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.event.RequestStatusChangedEvent;
import site.siyoung.beforeaibe.repository.WebhookRepository;
import site.siyoung.beforeaibe.webhook.WebhookClient;
import site.siyoung.beforeaibe.webhook.WebhookPayloadFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// 요청 상태 변화가 커밋되면(AFTER_COMMIT) 등록된 webhook으로 비동기 발송한다.
// - 커밋 후에만 보내 롤백된 상태 변화가 새어나가지 않게 한다(RequestStreamService와 같은 철학).
// - @Async라 본 요청/트랜잭션을 지연시키지 않는다.
// - 알림은 부가기능이므로 발송 실패는 삼키고 로그만 남긴다.
// - webhook.enabled=false면 아무 것도 하지 않는다(테스트 격리·운영 토글).
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    // 노이즈가 큰 내부 전이(ANALYZING/REVIEWING/ANALYSIS_FAILED)는 제외하고
    // 승인 대기·실행 시작·종료·거부/실패/중단 등 의미 있는 전이만 알린다.
    private static final Set<RequestStatus> NOTIFIABLE = EnumSet.of(
            RequestStatus.PENDING_APPROVAL,
            RequestStatus.REAPPROVAL_REQUIRED,
            RequestStatus.RUNNING,
            RequestStatus.ROLLING_BACK,
            RequestStatus.COMPLETED,
            RequestStatus.PARTIALLY_COMPLETED,
            RequestStatus.FAILED,
            RequestStatus.ROLLED_BACK,
            RequestStatus.ROLLBACK_FAILED,
            RequestStatus.REJECTED,
            RequestStatus.VERIFICATION_FAILED,
            RequestStatus.KILLED);

    @Value("${webhook.enabled:true}")
    private boolean enabled;

    private final WebhookRepository webhookRepository;
    private final WebhookClient webhookClient;
    private final WebhookPayloadFactory payloadFactory;

    @Async("applicationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequestStatusChanged(RequestStatusChangedEvent event) {
        if (!enabled || !NOTIFIABLE.contains(event.newStatus())) {
            return;
        }
        List<Webhook> targets = webhookRepository.findByEnabledTrue();
        if (targets.isEmpty()) {
            return;
        }
        for (Webhook webhook : targets) {
            try {
                Object body = payloadFactory.build(webhook.getType(), event);
                boolean sent = webhookClient.post(webhook.getUrl(), body);
                if (!sent) {
                    log.warn("webhook 발송 실패: webhookId={}, type={}, status={}",
                            webhook.getId(), webhook.getType(), event.newStatus());
                }
            } catch (RuntimeException e) {
                // 한 대상의 실패가 다른 대상 발송을 막지 않게 한다
                log.warn("webhook 발송 중 예외: webhookId={}", webhook.getId(), e);
            }
        }
    }
}
