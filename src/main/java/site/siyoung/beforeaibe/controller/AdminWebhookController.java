package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.admin.CreateWebhookRequest;
import site.siyoung.beforeaibe.dto.admin.WebhookResponse;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.WebhookService;

import java.util.List;
import java.util.UUID;

// 요청 상태 알림 webhook 등록/조회/삭제. ADMIN + 내부망.
// 발송은 WebhookDispatcher가 요청 상태 변화 커밋 후 비동기로 처리한다.
@RestController
@RequestMapping("/api/v1/admin/webhooks")
@RequiredArgsConstructor
@InternalNetworkOnly
public class AdminWebhookController {

    private final WebhookService webhookService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookResponse create(@Valid @RequestBody CreateWebhookRequest request) {
        return webhookService.create(request);
    }

    @GetMapping
    public List<WebhookResponse> list() {
        return webhookService.list();
    }

    @DeleteMapping("/{webhookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID webhookId) {
        webhookService.delete(webhookId);
    }
}
