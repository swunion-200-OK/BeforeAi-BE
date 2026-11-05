package site.siyoung.beforeaibe.dto.admin;

// POST /admin/users 응답. initialPassword는 생성 시 1회만 노출한다 (ADM-01)
public record CreateUserResponse(UserView user, String initialPassword) {
}
