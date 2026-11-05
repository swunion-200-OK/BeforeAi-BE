package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class SpecVersionRepositoryTest {

    @Autowired
    private SpecVersionRepository specVersionRepository;

    @Autowired
    private TestEntityManager em;

    private Project project;
    private User user;

    private void persistVersions(int count) {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        user = em.persist(RepositoryTestSupport.user("MGR001", department));
        project = em.persist(RepositoryTestSupport.project(department));
        for (int version = 1; version <= count; version++) {
            em.persist(SpecVersion.builder()
                    .project(project)
                    .version(version)
                    .body("v" + version)
                    .createdBy(user)
                    .build());
        }
        em.flush();
        em.clear();
    }

    @Test
    void 가장_큰_version을_현재_기획서로_조회한다() {
        persistVersions(3);

        SpecVersion current = specVersionRepository.findTopByProjectIdOrderByVersionDesc(project.getId()).orElseThrow();

        assertThat(current.getVersion()).isEqualTo(3);
        assertThat(current.getBody()).isEqualTo("v3");
        assertThat(specVersionRepository.findByProjectIdAndVersion(project.getId(), 2).orElseThrow().getBody())
                .isEqualTo("v2");
        assertThat(specVersionRepository.findByProjectIdAndVersion(project.getId(), 9)).isEmpty();
    }

    @Test
    void 버전_이력을_최신순으로_커서_페이지를_나눠_조회한다() {
        persistVersions(3);

        List<SpecVersion> firstPage = specVersionRepository.findByProjectIdOrderByVersionDesc(project.getId(), Limit.of(2));
        List<SpecVersion> secondPage = specVersionRepository.findByProjectIdAndVersionLessThanOrderByVersionDesc(
                project.getId(), firstPage.getLast().getVersion(), Limit.of(2));

        assertThat(firstPage).extracting(SpecVersion::getVersion).containsExactly(3, 2);
        assertThat(secondPage).extracting(SpecVersion::getVersion).containsExactly(1);
    }
}
