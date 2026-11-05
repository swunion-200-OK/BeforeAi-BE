package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import site.siyoung.beforeaibe.entity.enums.Role;

// POST /admin/users — 계정 생성 (ADM-01)
public record CreateUserRequest(
        @NotBlank String employeeNumber,
        @NotBlank String name,
        UUID departmentId,
        @NotNull Role role
) {
}
