package site.siyoung.beforeaibe.dto.me;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OtpRegisterResponse(
        String secret,
        String otpauthUri,
        boolean verified
) {

    public static OtpRegisterResponse issued(String secret, String otpauthUri) {
        return new OtpRegisterResponse(secret, otpauthUri, false);
    }

    public static OtpRegisterResponse verifiedResponse() {
        return new OtpRegisterResponse(null, null, true);
    }
}
