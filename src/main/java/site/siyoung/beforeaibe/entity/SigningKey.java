package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;

import java.util.UUID;

// 승인자 기기에서 만든 서명용 공개키. 개인키는 기기 밖으로 나오지 않으므로 공개키만 보관함
@Getter
@Entity
@Table(name = "signing_keys")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SigningKey extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String algorithm;

    // Base64로 인코딩한 Ed25519 raw 공개키(32바이트)
    @Column(nullable = false, unique = true, length = 100)
    private String publicKey;

    @Column(length = 100)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SigningKeyStatus status;

    @Builder
    private SigningKey(User user, String algorithm, String publicKey, String deviceName, SigningKeyStatus status) {
        this.user = user;
        this.algorithm = algorithm;
        this.publicKey = publicKey;
        this.deviceName = deviceName;
        this.status = status;
    }

    // 관리자 등록: 웹쉘 정책에 공개키가 반영됨 (ADM-09)
    public void register() {
        this.status = SigningKeyStatus.REGISTERED;
    }

    // 관리자 폐기: 웹쉘 정책에서 제거됨 (ADM-09)
    public void revoke() {
        this.status = SigningKeyStatus.REVOKED;
    }
}
