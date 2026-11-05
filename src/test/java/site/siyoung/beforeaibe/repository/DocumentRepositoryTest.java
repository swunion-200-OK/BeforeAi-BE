package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Document;
import site.siyoung.beforeaibe.entity.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class DocumentRepositoryTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private TestEntityManager em;

    private Project persistProject() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        return em.persist(RepositoryTestSupport.project(department));
    }

    private static Document document(Project project, String path) {
        return Document.builder().project(project).title("운영 안내").path(path).currentVersion(1).build();
    }

    @Test
    void 프로젝트와_경로로_문서를_찾는다() {
        Project project = persistProject();
        Project other = persistProject();
        em.persist(document(project, "docs/login.md"));
        em.flush();
        em.clear();

        assertThat(documentRepository.findByProject_IdAndPath(project.getId(), "docs/login.md")).isPresent();
        assertThat(documentRepository.findByProject_IdAndPath(other.getId(), "docs/login.md")).isEmpty();
    }

    @Test
    void 프로젝트_문서를_커서_페이지로_나눠_조회한다() {
        Project project = persistProject();
        Project other = persistProject();
        em.persist(document(project, "docs/a.md"));
        em.persist(document(project, "docs/b.md"));
        em.persist(document(project, "docs/c.md"));
        em.persist(document(other, "docs/other.md"));
        em.flush();
        em.clear();

        List<Document> firstPage = documentRepository.findByProject_IdOrderByCreatedAtDescIdDesc(project.getId(),
                Limit.of(2));
        Document last = firstPage.getLast();
        List<Document> secondPage = documentRepository.findPageAfter(project.getId(), last.getCreatedAt(),
                last.getId(), Limit.of(2));

        List<UUID> ids = new ArrayList<>();
        firstPage.forEach(document -> ids.add(document.getId()));
        secondPage.forEach(document -> ids.add(document.getId()));
        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(firstPage).extracting(Document::getCreatedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void 같은_프로젝트에_같은_경로의_문서는_저장되지_않는다() {
        Project project = persistProject();
        documentRepository.saveAndFlush(document(project, "docs/login.md"));

        assertThatThrownBy(() -> documentRepository.saveAndFlush(document(project, "docs/login.md")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
