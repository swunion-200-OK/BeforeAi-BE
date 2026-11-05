package site.siyoung.beforeaibe.dto.me;

// otpCode가 없으면 비밀키 발급 단계, 있으면 OTP 앱에 등록한 뒤 확인 단계
public record OtpRegisterRequest(String otpCode) {
}
