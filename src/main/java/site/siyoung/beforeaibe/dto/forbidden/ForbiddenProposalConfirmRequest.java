package site.siyoung.beforeaibe.dto.forbidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
public record ForbiddenProposalConfirmRequest(@NotNull @Valid AdminProof adminProof) {}
