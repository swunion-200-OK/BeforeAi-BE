package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import site.siyoung.beforeaibe.dto.webshell.WebshellPublicKeyResponse;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebshellPublicKeyServiceTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void 설정된_공개키와_SHA256_지문을_돌려준다() {
        WebshellPublicKeyResponse response = new WebshellPublicKeyService(KEY, KEY).getPublicKey();

        assertThat(response.encryption().algorithm()).isEqualTo("X25519");
        assertThat(response.signing().algorithm()).isEqualTo("Ed25519");
        assertThat(response.encryption().publicKey()).isEqualTo(KEY);
        // 32바이트 0의 SHA-256
        assertThat(response.encryption().fingerprint())
                .isEqualTo("sha256:66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925");
    }

    @Test
    void 공개키가_설정되지_않았으면_웹쉘을_찾지_못한다() {
        WebshellPublicKeyService service = new WebshellPublicKeyService("", KEY);

        assertThatThrownBy(service::getPublicKey)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.WEBSHELL_UNAVAILABLE);
    }
}
