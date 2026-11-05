package site.siyoung.beforeaibe.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.admin.CreateUserRequest;
import site.siyoung.beforeaibe.dto.admin.CreateUserResponse;
import site.siyoung.beforeaibe.dto.admin.ResetPasswordResponse;
import site.siyoung.beforeaibe.dto.admin.UpdateUserRequest;
import site.siyoung.beforeaibe.dto.admin.UserView;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.UserAdminService;

// 관리자 계정 관리 (ADM-01, ADM-02). ADMIN 권한은 SecurityConfig의 /api/v1/admin/** 매처로 보호됨
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@InternalNetworkOnly
public class AdminUserController {

    private final UserAdminService userAdminService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return userAdminService.createUser(request);
    }

    @GetMapping
    public CursorPageResponse<UserView> listUsers(
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return userAdminService.listUsers(departmentId, role, cursor, limit);
    }

    @PatchMapping("/{userId}")
    public UserView updateUser(@PathVariable UUID userId, @RequestBody UpdateUserRequest request) {
        return userAdminService.updateUser(userId, request);
    }

    @PostMapping("/{userId}/reset-password")
    public ResetPasswordResponse resetPassword(@PathVariable UUID userId) {
        return userAdminService.resetPassword(userId);
    }
}
