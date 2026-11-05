package site.siyoung.beforeaibe.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.admin.AdminSigningKeyView;
import site.siyoung.beforeaibe.dto.admin.RegisterKeyRequest;
import site.siyoung.beforeaibe.dto.admin.RegisterKeyResponse;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.service.SigningKeyAdminService;

// 승인자 공개키 웹쉘 정책 등록/폐기 (ADM-09). ADMIN + 내부망
@RestController
@RequestMapping("/api/v1/admin/signing-keys")
@RequiredArgsConstructor
@InternalNetworkOnly
public class AdminSigningKeyController {

    private final SigningKeyAdminService signingKeyAdminService;

    @GetMapping
    public CursorPageResponse<AdminSigningKeyView> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return signingKeyAdminService.list(cursor, limit);
    }

    @PostMapping("/{keyId}/register")
    public RegisterKeyResponse register(@AuthenticationPrincipal AuthUser authUser,
                                        @PathVariable UUID keyId,
                                        @Valid @RequestBody RegisterKeyRequest request) {
        return signingKeyAdminService.register(authUser.id(), keyId, request.adminProof());
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID keyId) {
        signingKeyAdminService.revoke(keyId);
    }
}
