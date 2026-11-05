package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.ApprovalDocument;
import site.siyoung.beforeaibe.entity.ApprovalDocumentVersion;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ApprovalDocumentRepositoryTest {

    @Autowired
    private ApprovalDocumentRepository approvalDocumentRepository;

    @Autowired
    private ApprovalDocumentVersionRepository approvalDocumentVersionRepository;

    @Autowired
    private TestEntityManager em;

    private Plan persistPlan(Request request) {
        Plan plan = RepositoryTestSupport.plan(request);
        plan.addTask(RepositoryTestSupport.codeTask(1));
        return em.persist(plan);
    }

    @Test
    void 요청의_결재_문서와_판_이력을_최신순으로_조회한다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan first = persistPlan(request);
        Plan second = persistPlan(request);
        ApprovalDocument document = em.persist(ApprovalDocument.builder().request(request).currentVersion(2).build());
        em.persist(ApprovalDocumentVersion.builder().document(document).version(1).plan(first).build());
        em.persist(ApprovalDocumentVersion.builder().document(document).version(2).plan(second).build());
        em.flush();
        em.clear();

        ApprovalDocument found = approvalDocumentRepository.findByRequest_Id(request.getId()).orElseThrow();
        List<ApprovalDocumentVersion> versions =
                approvalDocumentVersionRepository.findByDocument_IdOrderByVersionDesc(found.getId());

        assertThat(found.getCurrentVersion()).isEqualTo(2);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(versions).extracting(ApprovalDocumentVersion::getVersion).containsExactly(2, 1);
        assertThat(versions.getLast().getPlan().getId()).isEqualTo(first.getId());
        assertThat(versions.getLast().getCreatedAt()).isNotNull();
        assertThat(approvalDocumentVersionRepository.findByDocument_IdAndVersion(found.getId(), 1).orElseThrow()
                .getPlan().getId()).isEqualTo(first.getId());
        assertThat(approvalDocumentVersionRepository.findByDocument_IdAndVersion(found.getId(), 9)).isEmpty();
    }

    @Test
    void 한_요청에_결재_문서가_두_개면_저장되지_않는다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        em.persist(ApprovalDocument.builder().request(request).currentVersion(1).build());
        em.flush();

        assertThatThrownBy(() -> {
            approvalDocumentRepository.saveAndFlush(ApprovalDocument.builder().request(request).currentVersion(1).build());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_문서에서_판_번호가_겹치면_저장되지_않는다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan first = persistPlan(request);
        Plan second = persistPlan(request);
        ApprovalDocument document = em.persist(ApprovalDocument.builder().request(request).currentVersion(1).build());
        em.persist(ApprovalDocumentVersion.builder().document(document).version(1).plan(first).build());
        em.flush();

        assertThatThrownBy(() -> approvalDocumentVersionRepository.saveAndFlush(
                ApprovalDocumentVersion.builder().document(document).version(1).plan(second).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
