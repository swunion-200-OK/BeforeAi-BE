package site.siyoung.beforeaibe.dto.forbidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import site.siyoung.beforeaibe.dto.admin.AdminProof;
// 서명 없는 해제 요청은 다음 서명 정책에 포함되며 금지는 계속 유지됨.
public record ForbiddenRuleReleaseRequest(@NotBlank String reason, @Valid AdminProof adminProof) {}
