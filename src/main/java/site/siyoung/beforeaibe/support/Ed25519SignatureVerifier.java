package site.siyoung.beforeaibe.support;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

// 기기 서명 공개키(Base64 raw 32바이트)로 Ed25519 서명을 검증한다.
// 서명 형식이 잘못된 경우와 서명이 맞지 않는 경우를 호출부가 다른 오류로 응답할 수 있도록 결과를 나눠 돌려준다.
@Component
public class Ed25519SignatureVerifier {

    private static final String ALGORITHM = "Ed25519";
    private static final int ED25519_SIGNATURE_BYTES = 64;
    // raw Ed25519 공개키 앞에 붙여 X.509(SubjectPublicKeyInfo) 형식으로 만드는 고정 헤더
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    public enum Result {
        VALID,
        INVALID_SIGNATURE,
        MALFORMED
    }

    public Result verify(String rawPublicKeyBase64, byte[] message, String signatureBase64) {
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            return Result.MALFORMED;
        }
        if (signature.length != ED25519_SIGNATURE_BYTES) {
            return Result.MALFORMED;
        }

        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(toPublicKey(rawPublicKeyBase64));
            verifier.update(message);
            return verifier.verify(signature) ? Result.VALID : Result.INVALID_SIGNATURE;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 저장된 공개키는 제출 시 검증했으므로 여기서 실패하면 서명 값 자체가 Ed25519로 읽히지 않는 경우임
            return Result.INVALID_SIGNATURE;
        }
    }

    private PublicKey toPublicKey(String rawPublicKeyBase64) throws GeneralSecurityException {
        byte[] raw = Base64.getDecoder().decode(rawPublicKeyBase64);
        byte[] encoded = new byte[ED25519_X509_PREFIX.length + raw.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, raw.length);
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    }
}
