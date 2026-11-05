package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;

// 계정 조회/변경 응답의 사용자 표현 (3.2 admin/users)
public record UserView(
        UUID id,
        String employeeNumber,
        String name,
        Role role,
        UUID departmentId,
        boolean active
) {

    public static UserView from(User user) {
        return new UserView(user.getId(), user.getEmployeeNumber(), user.getName(),
                user.getRole(), user.getDepartmentId(), user.isActive());
    }
}
