package site.siyoung.beforeaibe.dto.request;

import site.siyoung.beforeaibe.entity.User;

import java.util.UUID;

public record RequesterResponse(UUID id, String name, UUID departmentId) {

    public static RequesterResponse from(User user) {
        return new RequesterResponse(user.getId(), user.getName(), user.getDepartmentId());
    }
}
