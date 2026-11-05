package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

import site.siyoung.beforeaibe.entity.enums.Role;

// PATCH /admin/users/{userId} — 전달된 항목만 변경하는 PATCH (ADM-02). 모든 필드 nullable
public record UpdateUserRequest(
        Role role,
        UUID departmentId,
        Boolean active
) {
}
