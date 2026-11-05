package site.siyoung.beforeaibe.dto.forbidden;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import java.util.UUID;
public record ForbiddenProposalConfirmResponse(UUID proposalId, ForbiddenProposalStatus status,
        ForbiddenRuleResponse rule, int policyVersion) {}
