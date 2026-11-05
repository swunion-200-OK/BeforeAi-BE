package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.me.MeResponse;
import site.siyoung.beforeaibe.dto.me.OtpRegisterRequest;
import site.siyoung.beforeaibe.dto.me.OtpRegisterResponse;
import site.siyoung.beforeaibe.dto.me.PasswordChangeRequest;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeService {

    private final UserRepository userRepository;
    private final SigningKeyRepository signingKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    public MeResponse getMe(UUID userId, Network network) {
        User user = findUser(userId);
        boolean hasRegisteredSigningKey =
                signingKeyRepository.existsByUserIdAndStatus(userId, SigningKeyStatus.REGISTERED);
        return MeResponse.of(user, network, hasRegisteredSigningKey);
    }

    @Transactional
    public void changePassword(UUID userId, PasswordChangeRequest request) {
        User user = findUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "현재 비밀번호가 올바르지 않습니다");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    // otpCode 없이 호출하면 비밀키 발급, otpCode와 함께 호출하면 등록 확인. 등록을 마친 뒤에는 다시 등록할 수 없음
    @Transactional
    public OtpRegisterResponse registerOtp(UUID userId, OtpRegisterRequest request) {
        User user = findUser(userId);
        if (user.isOtpEnabled()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "이미 OTP를 등록했습니다");
        }

        String otpCode = request == null ? null : request.otpCode();
        if (otpCode == null || otpCode.isBlank()) {
            String secret = otpService.generateSecret();
            user.issueOtpSecret(secret);
            return OtpRegisterResponse.issued(secret, otpService.otpauthUri(user.getEmployeeNumber(), secret));
        }

        if (user.getOtpSecret() == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "먼저 OTP 비밀키를 발급받아야 합니다");
        }
        // 이미 로그인한 상태라 401을 쓰면 클라이언트가 세션 만료로 오해할 수 있어 입력 오류(400)로 응답
        if (!otpService.verify(user.getOtpSecret(), otpCode)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "OTP 코드가 올바르지 않습니다");
        }
        user.enableOtp();
        return OtpRegisterResponse.verifiedResponse();
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }
}
