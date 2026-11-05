package site.siyoung.beforeaibe.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OtpServiceTest {

    private final OtpService otpService = new OtpService();

    @Test
    void 현재_시각의_TOTP_코드는_통과하고_틀린_코드는_거부한다() throws Exception {
        String secret = otpService.generateSecret();
        String code = new DefaultCodeGenerator().generate(secret, Instant.now().getEpochSecond() / 30);

        assertThat(otpService.verify(secret, code)).isTrue();
        assertThat(otpService.verify(secret, "000000".equals(code) ? "111111" : "000000")).isFalse();
        assertThat(otpService.verify(null, code)).isFalse();
    }

    @Test
    void OTP_앱_등록용_URI에_발급자와_비밀키를_담는다() {
        String uri = otpService.otpauthUri("EMP001", "JBSWY3DPEHPK3PXP");

        assertThat(uri).startsWith("otpauth://totp/BeforeAI");
        assertThat(uri).contains("EMP001", "secret=JBSWY3DPEHPK3PXP", "issuer=BeforeAI");
    }
}
