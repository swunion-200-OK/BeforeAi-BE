package site.siyoung.beforeaibe.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.admin.KillAllRequest;
import site.siyoung.beforeaibe.dto.admin.KillAllResponse;
import site.siyoung.beforeaibe.service.KillSwitchService;

// 전사 실행 일괄 중단 (CORE-11). ADMIN 전용이지만 긴급 상황을 위해 외부망에서도 호출 가능하므로 @InternalNetworkOnly 미부착
@RestController
@RequestMapping("/api/v1/admin/kill-all")
@RequiredArgsConstructor
public class AdminKillController {

    private final KillSwitchService killSwitchService;

    @PostMapping
    public KillAllResponse killAll(@Valid @RequestBody KillAllRequest request) {
        return killSwitchService.killAll(request.reason());
    }
}
