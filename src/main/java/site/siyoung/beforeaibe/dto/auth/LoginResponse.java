package site.siyoung.beforeaibe.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

// 내부망: otpRequired=false + accessToken + passwordChangeRequired / 외부망: otpRequired=true + temporaryToken
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean otpRequired,
        String accessToken,
        String temporaryToken,
        Boolean passwordChangeRequired
) {

    public static LoginResponse from(AuthTokens tokens) {
        return new LoginResponse(tokens.otpRequired(), tokens.accessToken(), tokens.temporaryToken(),
                tokens.passwordChangeRequired());
    }
}
