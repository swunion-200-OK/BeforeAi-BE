package site.siyoung.beforeaibe.entity.value;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 승인 서명(payload + signature)을 실행기 공개키로 암호화한 값. 웹 서비스는 열어 보지 않고 전달만 함
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalEnvelope {

    @Column(name = "envelope_alg", nullable = false, length = 50)
    private String alg;

    @Column(name = "envelope_encapsulated_key", nullable = false, columnDefinition = "text")
    private String encapsulatedKey;

    @Column(name = "envelope_ciphertext", nullable = false, columnDefinition = "text")
    private String ciphertext;

    @Builder
    private ApprovalEnvelope(String alg, String encapsulatedKey, String ciphertext) {
        this.alg = alg;
        this.encapsulatedKey = encapsulatedKey;
        this.ciphertext = ciphertext;
    }
}
