package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

import site.siyoung.beforeaibe.entity.Department;

// 부서 조회/생성 응답
public record DepartmentView(UUID id, String name) {

    public static DepartmentView from(Department department) {
        return new DepartmentView(department.getId(), department.getName());
    }
}
