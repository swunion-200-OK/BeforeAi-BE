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
import site.siyoung.beforeaibe.entity.DocumentVersion;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class DocumentVersionRepositoryTest {

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private TestEntityManager em;

    private Document document;
    private Request first;
    private Request second;

    // v1은 first 요청, v2·v3은 second 요청이 만든 버전
    private void persistVersions() {
        first = RepositoryTestSupport.persistRequest(em);
        second = RepositoryTestSupport.persistRequest(em);
        Department department = em.persist(RepositoryTestSupport.department("운영팀"));
        Project project = em.persist(RepositoryTestSupport.project(department));
        document = em.persist(Document.builder()
                .project(project).title("운영 안내").path("docs/login.md").currentVersion(3).build());
        em.persist(version(1, first));
        em.persist(version(2, second));
        em.persist(version(3, second));
        em.flush();
        em.clear();
    }

    private DocumentVersion version(int version, Request request) {
        return DocumentVersion.builder().document(document).version(version).body("v" + version).request(request)
                .build();
    }

    @Test
    void 버전_이력을_최신순으로_커서_페이지를_나눠_조회한다() {
        persistVersions();

        List<DocumentVersion> firstPage = documentVersionRepository.findByDocument_IdOrderByVersionDesc(
                document.getId(), Limit.of(2));
        List<DocumentVersion> secondPage = documentVersionRepository
                .findByDocument_IdAndVersionLessThanOrderByVersionDesc(document.getId(),
                        firstPage.getLast().getVersion(), Limit.of(2));

        assertThat(firstPage).extracting(DocumentVersion::getVersion).containsExactly(3, 2);
        assertThat(secondPage).extracting(DocumentVersion::getVersion).containsExactly(1);
        assertThat(documentVersionRepository.findByDocument_IdAndVersion(document.getId(), 2).orElseThrow()
                .getBody()).isEqualTo("v2");
        assertThat(documentVersionRepository.findByDocument_IdAndVersion(document.getId(), 9)).isEmpty();
    }

    @Test
    void 요청이_처음_만든_버전과_그_직전_버전을_찾는다() {
        persistVersions();

        DocumentVersion applied = documentVersionRepository
                .findFirstByDocument_IdAndRequest_IdOrderByVersionAsc(document.getId(), second.getId()).orElseThrow();
        DocumentVersion previous = documentVersionRepository
                .findFirstByDocument_IdAndVersionLessThanOrderByVersionDesc(document.getId(), applied.getVersion())
                .orElseThrow();

        assertThat(applied.getVersion()).isEqualTo(2);
        assertThat(previous.getVersion()).isEqualTo(1);
        assertThat(documentVersionRepository
                .findFirstByDocument_IdAndVersionLessThanOrderByVersionDesc(document.getId(), 1)).isEmpty();
    }

    @Test
    void 같은_문서에_같은_버전은_저장되지_않는다() {
        persistVersions();
        document = em.find(Document.class, document.getId());

        assertThatThrownBy(() -> documentVersionRepository.saveAndFlush(version(1, em.find(Request.class,
                first.getId()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
