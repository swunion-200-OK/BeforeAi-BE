package site.siyoung.beforeaibe.dto.me;

import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.Role;

import java.util.UUID;

public record MeResponse(
        UUID id,
        String employeeNumber,
        String name,
        Role role,
        UUID departmentId,
        boolean active,
        Network network,
        boolean passwordChangeRequired,
        boolean hasRegisteredSigningKey
) {

    public static MeResponse of(User user, Network network, boolean hasRegisteredSigningKey) {
        return new MeResponse(user.getId(), user.getEmployeeNumber(), user.getName(), user.getRole(),
                user.getDepartmentId(), user.isActive(), network,
                user.isPasswordChangeRequired(), hasRegisteredSigningKey);
    }
}
