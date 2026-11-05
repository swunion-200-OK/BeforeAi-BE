package site.siyoung.beforeaibe.dto.admin;

import jakarta.validation.constraints.NotBlank;

// POST /admin/kill-all — 전사 실행 일괄 중단 (CORE-11)
public record KillAllRequest(@NotBlank String reason) {
}
