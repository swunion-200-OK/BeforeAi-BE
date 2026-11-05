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
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.UUID;

// 리프레시 토큰은 원문 대신 SHA-256 해시만 저장. 재발급 때마다 새 행을 만들고 이전 행은 폐기(로테이션)하므로
// createdAt이 곧 그 세션의 마지막 활동 시각이 되고, 외부망 30분 비활동 만료 판단에 씀
@Getter
@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    // 세션을 연 접속 환경
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Network network;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Builder
    private RefreshToken(User user, String tokenHash, Network network, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.network = network;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
