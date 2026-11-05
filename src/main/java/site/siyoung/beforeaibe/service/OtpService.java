package site.siyoung.beforeaibe.service;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

// TOTP(RFC 6238, SHA1·6자리·30초) 비밀키 발급과 코드 확인. 앞뒤 1구간까지 시계 오차를 허용함(라이브러리 기본값)
@Service
public class OtpService {

    private static final String ISSUER = "BeforeAI";
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier =
            new DefaultCodeVerifier(new DefaultCodeGenerator(HashingAlgorithm.SHA1, DIGITS), new SystemTimeProvider());

    public String generateSecret() {
        return secretGenerator.generate();
    }

    // OTP 앱 등록용 URI (예: otpauth://totp/BeforeAI:EMP001?secret=...&issuer=BeforeAI)
    public String otpauthUri(String employeeNumber, String secret) {
        return new QrData.Builder()
                .label(ISSUER + ":" + employeeNumber)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(DIGITS)
                .period(PERIOD_SECONDS)
                .build()
                .getUri();
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code.trim());
    }
}
