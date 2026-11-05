package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.webshell.ExecutionEventRequest;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.ExecutionEventService;

import java.util.UUID;

// 웹쉘 → 웹 서비스 콜백 (API 명세서 5 콜백). 사용자 토큰이 아니라 웹쉘 클라이언트 인증서(mTLS)로 인증함 (SecurityConfig)
@RestController
@RequestMapping("/api/v1/internal/executions")
@RequiredArgsConstructor
public class InternalExecutionController {

    private final ExecutionEventService executionEventService;

    @InternalNetworkOnly
    @PostMapping("/{executionId}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receiveEvent(@PathVariable UUID executionId, @Valid @RequestBody ExecutionEventRequest request) {
        executionEventService.receive(executionId, request);
    }
}
