package site.siyoung.beforeaibe.dto.project;

import jakarta.validation.constraints.NotBlank;

// 반려 사유는 필수
public record SpecChangeRequestRejectRequest(@NotBlank String reason) {
}
