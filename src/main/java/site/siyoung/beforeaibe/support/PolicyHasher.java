package site.siyoung.beforeaibe.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.service.PlanHashService;
import java.util.Map;

// 정책 payload는 문자열·정수·배열·객체만 포함하므로 Plan과 같은 JCS 구현을 사용함.
@Component
@RequiredArgsConstructor
public class PolicyHasher {
    private final PlanHashService canonicalJson;
    public String hash(Map<String, Object> payload) {
        return canonicalJson.hash(payload);
    }
}
