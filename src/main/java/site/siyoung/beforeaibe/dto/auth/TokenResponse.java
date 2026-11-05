package site.siyoung.beforeaibe.dto.auth;

public record TokenResponse(String accessToken) {

    public static TokenResponse from(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken());
    }
}
