package site.siyoung.beforeaibe.webshell;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
import java.util.Map;
public interface PolicyDeploymentClient {
    Result apply(Map<String, Object> policy, AdminProof proof, String expectedHash);
    record Result(int version, String policyHash, boolean applied) {}
}
