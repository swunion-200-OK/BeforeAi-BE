package site.siyoung.beforeaibe.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.me.MeResponse;
import site.siyoung.beforeaibe.dto.me.OtpRegisterRequest;
import site.siyoung.beforeaibe.dto.me.OtpRegisterResponse;
import site.siyoung.beforeaibe.dto.me.PasswordChangeRequest;
import site.siyoung.beforeaibe.dto.me.SigningKeyCreateRequest;
import site.siyoung.beforeaibe.dto.me.SigningKeyResponse;
import site.siyoung.beforeaibe.security.AuthUser;
import site.siyoung.beforeaibe.security.InternalNetworkOnly;
import site.siyoung.beforeaibe.security.NetworkResolver;
import site.siyoung.beforeaibe.service.MeService;
import site.siyoung.beforeaibe.service.SigningKeyService;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final MeService meService;
    private final SigningKeyService signingKeyService;
    private final NetworkResolver networkResolver;

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthUser authUser, HttpServletRequest httpRequest) {
        return meService.getMe(authUser.id(), networkResolver.resolve(httpRequest));
    }

    @InternalNetworkOnly
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser authUser,
                                               @Valid @RequestBody PasswordChangeRequest request) {
        meService.changePassword(authUser.id(), request);
        return ResponseEntity.noContent().build();
    }

    @InternalNetworkOnly
    @PostMapping("/otp")
    public OtpRegisterResponse registerOtp(@AuthenticationPrincipal AuthUser authUser,
                                           @RequestBody(required = false) OtpRegisterRequest request) {
        return meService.registerOtp(authUser.id(), request);
    }

    @InternalNetworkOnly
    @PostMapping("/signing-keys")
    public ResponseEntity<SigningKeyResponse> createSigningKey(@AuthenticationPrincipal AuthUser authUser,
                                                               @Valid @RequestBody SigningKeyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(signingKeyService.create(authUser.id(), request));
    }

    // 목록 API 공통 페이지 규칙: ?cursor=&limit= (기본 20, 최대 100)
    @GetMapping("/signing-keys")
    public CursorPageResponse<SigningKeyResponse> signingKeys(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return signingKeyService.list(authUser.id(), cursor, limit);
    }
}
