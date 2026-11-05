package site.siyoung.beforeaibe.security;

import site.siyoung.beforeaibe.entity.enums.Role;

import java.util.UUID;

// 액세스 토큰에서 꺼낸 로그인 사용자. 컨트롤러에서 @AuthenticationPrincipal로 받음
public record AuthUser(UUID id, Role role, boolean passwordChangeRequired) {
}
