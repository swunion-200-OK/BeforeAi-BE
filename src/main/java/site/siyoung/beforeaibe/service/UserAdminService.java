package site.siyoung.beforeaibe.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import site.siyoung.beforeaibe.dto.admin.CreateUserRequest;
import site.siyoung.beforeaibe.dto.admin.CreateUserResponse;
import site.siyoung.beforeaibe.dto.admin.ResetPasswordResponse;
import site.siyoung.beforeaibe.dto.admin.UpdateUserRequest;
import site.siyoung.beforeaibe.dto.admin.UserView;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.support.CreatedAtCursor;
import site.siyoung.beforeaibe.support.InitialPasswordGenerator;

// 관리자 계정 관리 (ADM-01, ADM-02). 내부망 + ADMIN 권한에서만 호출됨(컨트롤러에서 보장)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAdminService {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final InitialPasswordGenerator initialPasswordGenerator;

    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmployeeNumber(request.employeeNumber())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "이미 사용 중인 사번입니다");
        }
        validateDepartment(request.departmentId());

        String initialPassword = initialPasswordGenerator.generate();
        User saved = userRepository.save(User.builder()
                .employeeNumber(request.employeeNumber())
                .name(request.name())
                .role(request.role())
                .departmentId(request.departmentId())
                .passwordHash(passwordEncoder.encode(initialPassword))
                .active(true)
                .passwordChangeRequired(true)
                .build());
        return new CreateUserResponse(UserView.from(saved), initialPassword);
    }

    public CursorPageResponse<UserView> listUsers(UUID departmentId, Role role, String cursor, int limit) {
        Limit fetchLimit = Limit.of(limit + 1);
        List<User> users;
        if (cursor == null || cursor.isBlank()) {
            users = userRepository.findFirstPage(departmentId, role, fetchLimit);
        } else {
            CreatedAtCursor decoded = CreatedAtCursor.decode(cursor);
            users = userRepository.findPageAfter(departmentId, role, decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = users.size() > limit;
        List<User> page = hasNext ? users.subList(0, limit) : users;
        String nextCursor = hasNext
                ? new CreatedAtCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new CursorPageResponse<>(page.stream().map(UserView::from).toList(), nextCursor);
    }

    @Transactional
    public UserView updateUser(UUID userId, UpdateUserRequest request) {
        User user = findUser(userId);
        validateDepartment(request.departmentId());
        user.updateByAdmin(request.role(), request.departmentId(), request.active());
        return UserView.from(user);
    }

    @Transactional
    public ResetPasswordResponse resetPassword(UUID userId) {
        User user = findUser(userId);
        String initialPassword = initialPasswordGenerator.generate();
        user.resetPassword(passwordEncoder.encode(initialPassword));
        return new ResetPasswordResponse(user.getId(), initialPassword);
    }

    private void validateDepartment(UUID departmentId) {
        if (departmentId != null && !departmentRepository.existsById(departmentId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다");
        }
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "계정을 찾을 수 없습니다"));
    }
}
