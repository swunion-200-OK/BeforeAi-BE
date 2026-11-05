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
import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;
import site.siyoung.beforeaibe.entity.value.AuditExportFilters;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class AuditExportRepositoryTest {

    @Autowired
    private AuditExportRepository auditExportRepository;

    @Autowired
    private TestEntityManager em;

    private AuditExport persistExport(User requester) {
        AuditExport export = em.persist(AuditExport.builder()
                .requester(requester).format(ExportFormat.CSV).status(ExportStatus.PROCESSING)
                .filters(new AuditExportFilters(null, null, null, null, null, null, null, null))
                .rowCount(0).build());
        em.flush();
        return export;
    }

    @Test
    void 내가_요청한_내보내기만_최신순으로_조회한다() {
        User me = em.persist(RepositoryTestSupport.user("EMP001"));
        User other = em.persist(RepositoryTestSupport.user("EMP002"));
        AuditExport older = persistExport(me);
        AuditExport newer = persistExport(me);
        persistExport(other);
        em.clear();

        List<AuditExport> page = auditExportRepository.findFirstPage(me.getId(), Limit.of(10));

        assertThat(page).extracting(AuditExport::getId).containsExactly(newer.getId(), older.getId());
        assertThat(auditExportRepository.findPageAfter(me.getId(), page.getFirst().getCreatedAt(),
                page.getFirst().getId(), Limit.of(10))).extracting(AuditExport::getId).containsExactly(older.getId());
    }

    @Test
    void 준비된_파일_내용을_저장하고_만료하면_지운다() {
        User me = em.persist(RepositoryTestSupport.user("EMP001"));
        AuditExport export = persistExport(me);
        export.markReady("audit-logs.csv", "a,b".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-11-04T05:20:00Z"));
        em.flush();
        em.clear();

        AuditExport ready = auditExportRepository.findById(export.getId()).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(ExportStatus.READY);
        assertThat(new String(ready.getFileContent(), StandardCharsets.UTF_8)).isEqualTo("a,b");
        assertThat(ready.isExpiredAt(Instant.parse("2026-11-04T05:20:00Z"))).isTrue();

        ready.expire();
        em.flush();
        em.clear();

        AuditExport expired = auditExportRepository.findById(export.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ExportStatus.EXPIRED);
        assertThat(expired.getFileContent()).isNull();
    }
}
