package site.siyoung.beforeaibe.dto.project;

import site.siyoung.beforeaibe.entity.User;

import java.util.UUID;

public record OwnerResponse(UUID id, String name) {

    public static OwnerResponse from(User user) {
        return new OwnerResponse(user.getId(), user.getName());
    }
}
