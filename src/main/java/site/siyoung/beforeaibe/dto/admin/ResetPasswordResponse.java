package site.siyoung.beforeaibe.dto.admin;

import java.util.UUID;

// POST /admin/users/{userId}/reset-password 응답 (ADM-01)
public record ResetPasswordResponse(UUID userId, String initialPassword) {
}
