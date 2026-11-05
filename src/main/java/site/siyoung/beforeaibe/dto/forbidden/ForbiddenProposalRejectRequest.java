package site.siyoung.beforeaibe.dto.forbidden;
import jakarta.validation.constraints.NotBlank;
public record ForbiddenProposalRejectRequest(@NotBlank String reason) {}
