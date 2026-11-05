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
import site.siyoung.beforeaibe.entity.ProjectOwner;
import site.siyoung.beforeaibe.entity.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectOwnerRepository projectOwnerRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 프로젝트를_최신순으로_커서_페이지를_나눠_조회한다() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        Project oldest = em.persistAndFlush(RepositoryTestSupport.project(department));
        Project middle = em.persistAndFlush(RepositoryTestSupport.project(department));
        Project newest = em.persistAndFlush(RepositoryTestSupport.project(department));
        em.clear();

        List<Project> firstPage = projectRepository.findAllByOrderByCreatedAtDescIdDesc(Limit.of(2));
        Project last = firstPage.getLast();
        List<Project> secondPage = projectRepository.findPageAfter(last.getCreatedAt(), last.getId(), Limit.of(2));

        assertThat(firstPage).extracting(Project::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(secondPage).extracting(Project::getId).containsExactly(oldest.getId());
    }

    @Test
    void 여러_프로젝트의_담당자를_사용자와_함께_한_번에_조회한다() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        User owner1 = em.persist(RepositoryTestSupport.user("EMP001", department));
        User owner2 = em.persist(RepositoryTestSupport.user("EMP002", department));
        Project first = RepositoryTestSupport.project(department);
        first.addOwner(owner1);
        first.addOwner(owner2);
        Project second = RepositoryTestSupport.project(department);
        second.addOwner(owner1);
        em.persist(first);
        em.persist(second);
        em.flush();
        em.clear();

        List<ProjectOwner> owners = projectOwnerRepository.findWithUserByProjectIdIn(
                List.of(first.getId(), second.getId()));

        assertThat(owners).hasSize(3);
        assertThat(owners).extracting(owner -> owner.getUser().getEmployeeNumber())
                .containsExactlyInAnyOrder("EMP001", "EMP002", "EMP001");
    }

    @Test
    void 잠금_조회로도_프로젝트를_찾는다() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        Project project = em.persistAndFlush(RepositoryTestSupport.project(department));
        em.clear();

        assertThat(projectRepository.findByIdForUpdate(project.getId())).isPresent();
    }
}
