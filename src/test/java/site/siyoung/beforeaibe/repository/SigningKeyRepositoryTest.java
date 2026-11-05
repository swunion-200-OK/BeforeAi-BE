package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class SigningKeyRepositoryTest {

    @Autowired
    private SigningKeyRepository signingKeyRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 공개키를_저장하고_사용자와_상태로_조회한다() {
        User user = em.persist(RepositoryTestSupport.user("EMP001"));
        SigningKey saved = signingKeyRepository.saveAndFlush(
                RepositoryTestSupport.signingKey(user, "key-a", SigningKeyStatus.PENDING));
        em.clear();

        SigningKey found = signingKeyRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getAlgorithm()).isEqualTo("Ed25519");
        assertThat(found.getPublicKey()).isEqualTo("key-a");
        assertThat(found.getDeviceName()).isEqualTo("업무용 노트북");
        assertThat(found.getStatus()).isEqualTo(SigningKeyStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(signingKeyRepository.existsByUserIdAndStatus(user.getId(), SigningKeyStatus.REGISTERED)).isFalse();
        assertThat(signingKeyRepository.existsByUserIdAndStatusIn(user.getId(),
                List.of(SigningKeyStatus.PENDING, SigningKeyStatus.REGISTERED))).isTrue();
        assertThat(signingKeyRepository.existsByPublicKey("key-a")).isTrue();
    }

    @Test
    void 같은_사용자에게_폐기되지_않은_키가_두_개면_저장되지_않는다() {
        User user = em.persist(RepositoryTestSupport.user("EMP001"));
        signingKeyRepository.saveAndFlush(RepositoryTestSupport.signingKey(user, "key-a", SigningKeyStatus.PENDING));

        assertThatThrownBy(() -> signingKeyRepository.saveAndFlush(
                RepositoryTestSupport.signingKey(user, "key-b", SigningKeyStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 폐기된_키가_있어도_새_키는_저장된다() {
        User user = em.persist(RepositoryTestSupport.user("EMP001"));
        signingKeyRepository.saveAndFlush(RepositoryTestSupport.signingKey(user, "key-a", SigningKeyStatus.REVOKED));

        SigningKey saved = signingKeyRepository.saveAndFlush(
                RepositoryTestSupport.signingKey(user, "key-b", SigningKeyStatus.PENDING));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void 최신순으로_커서_페이지를_나눠_조회한다() {
        User user = em.persist(RepositoryTestSupport.user("EMP001"));
        SigningKey oldest = signingKeyRepository.saveAndFlush(
                RepositoryTestSupport.signingKey(user, "key-a", SigningKeyStatus.REVOKED));
        SigningKey middle = signingKeyRepository.saveAndFlush(
                RepositoryTestSupport.signingKey(user, "key-b", SigningKeyStatus.REVOKED));
        SigningKey newest = signingKeyRepository.saveAndFlush(
                RepositoryTestSupport.signingKey(user, "key-c", SigningKeyStatus.PENDING));
        em.clear();

        List<SigningKey> firstPage = signingKeyRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId(), Limit.of(2));
        SigningKey last = firstPage.getLast();
        List<SigningKey> secondPage = signingKeyRepository.findPageAfter(
                user.getId(), last.getCreatedAt(), last.getId(), Limit.of(2));

        assertThat(firstPage).extracting(SigningKey::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(secondPage).extracting(SigningKey::getId).containsExactly(oldest.getId());
    }
}
