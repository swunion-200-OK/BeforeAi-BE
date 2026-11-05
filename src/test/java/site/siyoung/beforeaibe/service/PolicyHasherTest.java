package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.support.PolicyHasher;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyHasherTest {
    private final PolicyHasher hasher = new PolicyHasher(new PlanHashService());

    @Test
    void 객체_키_순서는_해시에_영향이_없고_금지_규칙과_공개키는_영향을_준다() {
        LinkedHashMap<String, Object> original = new LinkedHashMap<>();
        original.put("version", 1);
        original.put("preset", "MEDIUM");
        original.put("approvalMatrix", List.of());
        original.put("forbiddenRules", List.of());
        original.put("signingKeys", List.of());
        var reverse = new LinkedHashMap<String, Object>();
        original.sequencedEntrySet().reversed().forEach(e -> reverse.put(e.getKey(), e.getValue()));
        assertThat(hasher.hash(reverse)).isEqualTo(hasher.hash(original));
        var changedRule = new LinkedHashMap<>(original);
        changedRule.put("forbiddenRules", List.of(Map.of("target", "production", "status", "CONFIRMED")));
        assertThat(hasher.hash(changedRule)).isNotEqualTo(hasher.hash(original));
        var release = new LinkedHashMap<>(changedRule);
        release.put("forbiddenRules", List.of(Map.of("target", "production", "status", "RELEASE_PENDING")));
        assertThat(hasher.hash(release)).isNotEqualTo(hasher.hash(changedRule));
        var changedKey = new LinkedHashMap<>(original);
        changedKey.put("signingKeys", List.of(Map.of("keyId", "key", "publicKey", "public")));
        assertThat(hasher.hash(changedKey)).isNotEqualTo(hasher.hash(original));
    }
}
