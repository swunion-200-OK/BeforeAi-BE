package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.execution.ExecutionEventRequest;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.ExecutionEventService;

import java.util.UUID;

// 웹쉘 → 웹 서비스 콜백 (5장). 사용자 토큰 없이 열고(SecurityConfig), 내부망 출발지와 이벤트마다 붙은 웹쉘 서명으로 확인함.
// mTLS는 앱 밖(TLS 종단)에서 맡음
@RestController
@RequestMapping("/api/v1/internal/executions")
@RequiredArgsConstructor
public class InternalExecutionController {

    private final ExecutionEventService executionEventService;

    @InternalNetworkOnly
    @PostMapping("/{executionId}/events")
    public ResponseEntity<Void> receive(@PathVariable UUID executionId,
                                        @Valid @RequestBody ExecutionEventRequest event) {
        executionEventService.receive(executionId, event);
        return ResponseEntity.noContent().build();
    }
}
