package site.siyoung.beforeaibe.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;

// 정책(프리셋 + 승인 기준표)의 해시를 계산한다. 웹쉘 GET /v1/policies/current의 해시와 정합성을 비교하는 데 쓴다.
// 명세는 RFC 8785(JCS) 정규화 후 SHA-256을 요구하지만, 정식 JCS 라이브러리 도입 전까지는 키 정렬 직렬화로 근사한다.
@Component
public class PolicyHasher {

    // 맵 키 정렬을 켠 전용 ObjectMapper. 애플리케이션 전역 설정과 독립적으로 동작하도록 직접 생성
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public String hash(PolicyPreset preset, List<ApprovalMatrixRule> approvalMatrix) {
        try {
            String canonical = mapper.writeValueAsString(new PolicyPayload(preset, approvalMatrix));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("정책 직렬화에 실패했습니다", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record PolicyPayload(PolicyPreset preset, List<ApprovalMatrixRule> approvalMatrix) {
    }
}
