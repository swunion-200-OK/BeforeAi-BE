package site.siyoung.beforeaibe.dto.forbidden;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import java.util.UUID;
public record ForbiddenProposalStatusResponse(UUID proposalId, ForbiddenProposalStatus status) {}
