package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.admin.CreateWebhookRequest;
import site.siyoung.beforeaibe.dto.admin.WebhookResponse;
import site.siyoung.beforeaibe.entity.Webhook;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.WebhookRepository;

import java.util.List;
import java.util.UUID;

// 관리자 webhook 등록/조회/삭제 (ADMIN 전용). 발송 자체는 WebhookDispatcher가 한다.
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookRepository webhookRepository;

    @Transactional
    public WebhookResponse create(CreateWebhookRequest request) {
        Webhook webhook = webhookRepository.save(Webhook.builder()
                .name(request.name())
                .type(request.type())
                .url(request.url())
                .enabled(request.enabledOrDefault())
                .build());
        return WebhookResponse.from(webhook);
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> list() {
        return webhookRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(WebhookResponse::from)
                .toList();
    }

    @Transactional
    public void delete(UUID id) {
        if (!webhookRepository.existsById(id)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "webhook을 찾을 수 없습니다");
        }
        webhookRepository.deleteById(id);
    }
}
