package site.siyoung.beforeaibe.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.repository.ForbiddenRuleRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import java.util.*;

@Component
@RequiredArgsConstructor
public class PolicyPayloadFactory {
    private final ForbiddenRuleRepository forbiddenRules;
    private final SigningKeyRepository signingKeys;

    public Map<String, Object> create(int version, PolicyPreset preset, List<ApprovalMatrixRule> matrix) {
        List<Map<String, Object>> rules = forbiddenRules.findAll().stream()
                .sorted(Comparator.comparing(r -> r.getId().toString()))
                .map(r -> Map.<String, Object>of("id", r.getId().toString(),
                        "departmentId", r.getDepartment().getId().toString(), "taskType", r.getTaskType().name(),
                        "target", r.getTarget(), "reason", r.getReason(), "status", r.getStatus().name())).toList();
        List<Map<String, Object>> keys = signingKeys.findAll().stream()
                .filter(k -> k.getStatus() == SigningKeyStatus.REGISTERED)
                .sorted(Comparator.comparing(k -> k.getId().toString()))
                .map(k -> Map.<String, Object>of("keyId", k.getId().toString(), "ownerId", k.getUser().getId().toString(),
                        "algorithm", k.getAlgorithm(), "publicKey", k.getPublicKey())).toList();
        return Map.of("version", version, "preset", preset.name(), "approvalMatrix", matrixPayload(matrix),
                "forbiddenRules", rules, "signingKeys", keys);
    }

    public static List<Map<String, Object>> matrixPayload(List<ApprovalMatrixRule> matrix) {
        return matrix.stream().map(r -> Map.<String, Object>of("taskType", r.taskType().name(), "risk", r.risk().name(),
                "requiredApprovers", r.requiredApprovers().stream().map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rule", a.rule().name());
                    item.put("count", a.count());
                    if (a.candidates() != null) item.put("candidates", a.candidates().stream().map(UUID::toString).toList());
                    return item;
                }).toList())).toList();
    }
}
