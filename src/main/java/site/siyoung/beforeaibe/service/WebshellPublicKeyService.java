package site.siyoung.beforeaibe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.siyoung.beforeaibe.dto.webshell.WebshellPublicKeyResponse;
import site.siyoung.beforeaibe.dto.webshell.WebshellPublicKeyResponse.KeyInfo;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

// 웹쉘 공개키는 설정값으로 둠. 클라이언트는 키 등록 때 내부망에서 받아 고정(pinning)해 두고 이후 바꾸지 않음 (5.9-⑥)
@Service
public class WebshellPublicKeyService {

    private final String encryptionPublicKey;
    private final String signingPublicKey;

    public WebshellPublicKeyService(@Value("${webshell.encryption-public-key:}") String encryptionPublicKey,
                                    @Value("${webshell.signing-public-key:}") String signingPublicKey) {
        this.encryptionPublicKey = encryptionPublicKey;
        this.signingPublicKey = signingPublicKey;
    }

    public WebshellPublicKeyResponse getPublicKey() {
        return new WebshellPublicKeyResponse(
                keyInfo("X25519", encryptionPublicKey),
                keyInfo("Ed25519", signingPublicKey));
    }

    private KeyInfo keyInfo(String algorithm, String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE, "웹쉘을 찾을 수 없습니다");
        }
        return new KeyInfo(algorithm, publicKey, fingerprint(publicKey));
    }

    // 지문: Base64로 디코딩한 공개키 바이트의 SHA-256 (hex)
    private String fingerprint(String publicKey) {
        try {
            byte[] raw = Base64.getDecoder().decode(publicKey);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE, "웹쉘을 찾을 수 없습니다");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
