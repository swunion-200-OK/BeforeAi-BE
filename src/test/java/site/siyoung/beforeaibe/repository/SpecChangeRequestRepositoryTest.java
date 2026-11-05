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
import site.siyoung.beforeaibe.entity.SpecChangeRequest;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class SpecChangeRequestRepositoryTest {

    @Autowired
    private SpecChangeRequestRepository specChangeRequestRepository;

    @Autowired
    private TestEntityManager em;

    private SpecChangeRequest changeRequest(Project project, User requester, SpecChangeRequestStatus status) {
        return em.persistAndFlush(SpecChangeRequest.builder()
                .project(project)
                .requester(requester)
                .content("실패 메시지에 한글·영문 안내를 추가한다.")
                .reason("다국어 사용자 지원")
                .status(status)
                .build());
    }

    @Test
    void 프로젝트별로_상태를_걸러_최신순_커서_페이지로_조회한다() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        User requester = em.persist(RepositoryTestSupport.user("EMP001", department));
        Project project = em.persist(RepositoryTestSupport.project(department));
        Project other = em.persist(RepositoryTestSupport.project(department));
        SpecChangeRequest oldest = changeRequest(project, requester, SpecChangeRequestStatus.OPEN);
        SpecChangeRequest accepted = changeRequest(project, requester, SpecChangeRequestStatus.ACCEPTED);
        SpecChangeRequest newest = changeRequest(project, requester, SpecChangeRequestStatus.OPEN);
        changeRequest(other, requester, SpecChangeRequestStatus.OPEN);
        em.clear();

        List<SpecChangeRequest> all = specChangeRequestRepository.findPage(project.getId(), null, Limit.of(10));
        List<SpecChangeRequest> openFirst = specChangeRequestRepository.findPage(
                project.getId(), SpecChangeRequestStatus.OPEN, Limit.of(1));
        SpecChangeRequest last = openFirst.getLast();
        List<SpecChangeRequest> openSecond = specChangeRequestRepository.findPageAfter(
                project.getId(), SpecChangeRequestStatus.OPEN, last.getCreatedAt(), last.getId(), Limit.of(1));

        assertThat(all).extracting(SpecChangeRequest::getId)
                .containsExactly(newest.getId(), accepted.getId(), oldest.getId());
        assertThat(openFirst).extracting(SpecChangeRequest::getId).containsExactly(newest.getId());
        assertThat(openSecond).extracting(SpecChangeRequest::getId).containsExactly(oldest.getId());
    }

    @Test
    void 다른_프로젝트의_수정_요청은_ID로_조회되지_않는다() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        User requester = em.persist(RepositoryTestSupport.user("EMP001", department));
        Project project = em.persist(RepositoryTestSupport.project(department));
        Project other = em.persist(RepositoryTestSupport.project(department));
        SpecChangeRequest saved = changeRequest(project, requester, SpecChangeRequestStatus.OPEN);
        em.clear();

        assertThat(specChangeRequestRepository.findByIdAndProjectId(saved.getId(), project.getId())).isPresent();
        assertThat(specChangeRequestRepository.findByIdAndProjectId(saved.getId(), other.getId())).isEmpty();
    }
}
