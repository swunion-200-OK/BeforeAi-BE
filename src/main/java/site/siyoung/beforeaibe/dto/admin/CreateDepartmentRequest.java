package site.siyoung.beforeaibe.dto.admin;

import jakarta.validation.constraints.NotBlank;

// POST /admin/departments — 부서 생성 (ADM-02)
public record CreateDepartmentRequest(@NotBlank String name) {
}
