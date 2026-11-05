package site.siyoung.beforeaibe.webshell;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import java.util.Map;
// 테스트·개발에서 명시적으로 켠 경우에만 사용하며 실제 웹쉘 적용을 증명하지 않음.
@Component
@ConditionalOnProperty(name = "webshell.policy-stub", havingValue = "true")
public class StubPolicyDeploymentClient implements PolicyDeploymentClient {
    @Override
    public Result apply(Map<String, Object> policy, AdminProof proof, String expectedHash) {
        return new Result(((Number) policy.get("version")).intValue(), expectedHash, true);
    }
}
