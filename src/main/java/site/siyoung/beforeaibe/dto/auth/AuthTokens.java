package site.siyoung.beforeaibe.dto.auth;

// 인증 서비스가 컨트롤러에 넘기는 결과. refreshToken은 응답 본문이 아니라 HttpOnly 쿠키로 내려가므로 따로 담음
public record AuthTokens(
        boolean otpRequired,
        String temporaryToken,
        String accessToken,
        String refreshToken,
        Boolean passwordChangeRequired
) {

    public static AuthTokens otpRequired(String temporaryToken) {
        return new AuthTokens(true, temporaryToken, null, null, null);
    }

    public static AuthTokens issued(String accessToken, String refreshToken, boolean passwordChangeRequired) {
        return new AuthTokens(false, null, accessToken, refreshToken, passwordChangeRequired);
    }
}
