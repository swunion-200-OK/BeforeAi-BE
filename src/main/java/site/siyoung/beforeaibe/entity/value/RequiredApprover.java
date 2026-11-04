package site.siyoung.beforeaibe.entity.value;

import com.fasterxml.jackson.annotation.JsonInclude;
import site.siyoung.beforeaibe.enums.ApproverRule;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequiredApprover(
        ApproverRule rule,
        int count,
        List<UUID> candidates
) {
}
