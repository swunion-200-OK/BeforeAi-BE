package site.siyoung.beforeaibe.webshell;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import java.util.Map;
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "webshell.policy-stub", havingValue = "false", matchIfMissing = true)
public class HttpPolicyDeploymentClient implements PolicyDeploymentClient {
    private final WebshellClient client;
    @Override
    public Result apply(Map<String, Object> policy, AdminProof proof, String expectedHash) {
        return client.applyPolicy(policy, proof);
    }
}
