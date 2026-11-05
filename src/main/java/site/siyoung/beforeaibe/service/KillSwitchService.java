package site.siyoung.beforeaibe.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import site.siyoung.beforeaibe.dto.admin.KillAllResponse;
import site.siyoung.beforeaibe.webshell.WebshellPolicyClient;

// 전사 실행 일괄 중단 (CORE-11). ADMIN 전용, 외부망에서도 호출 가능.
// 실제 실행 세션의 상태 전환은 웹쉘 콜백(5장)으로 반영되므로 여기서는 웹쉘에 중단을 위임한다.
@Service
@RequiredArgsConstructor
public class KillSwitchService {

    private final WebshellPolicyClient webshellPolicyClient;

    public KillAllResponse killAll(String reason) {
        List<UUID> executionIds = webshellPolicyClient.killAll(reason);
        return new KillAllResponse("KILLED", executionIds);
    }
}
