package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.RefreshToken;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 토큰_해시로_조회하고_폐기_상태를_저장한다() {
        User user = em.persist(RepositoryTestSupport.user("EMP001"));
        Instant expiresAt = Instant.parse("2026-11-20T00:00:00Z");
        RefreshToken saved = refreshTokenRepository.saveAndFlush(RefreshToken.builder()
                .user(user)
                .tokenHash("a".repeat(64))
                .network(Network.EXTERNAL)
                .expiresAt(expiresAt)
                .build());
        Instant now = expiresAt.minus(Duration.ofDays(1));
        saved.revoke(now);
        refreshTokenRepository.flush();
        em.clear();

        RefreshToken found = refreshTokenRepository.findByTokenHash("a".repeat(64)).orElseThrow();

        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getNetwork()).isEqualTo(Network.EXTERNAL);
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.getRevokedAt()).isEqualTo(now);
        assertThat(found.isUsable(now)).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
