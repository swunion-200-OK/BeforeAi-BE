package site.siyoung.beforeaibe.dto.forbidden;
import site.siyoung.beforeaibe.entity.enums.ForbiddenRuleStatus;
import java.util.UUID;
public record ForbiddenRuleStatusResponse(UUID ruleId, ForbiddenRuleStatus status) {}
