package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class AuditLogRepositoryTest {

    private static final Instant MIN = Instant.EPOCH;
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TestEntityManager em;

    private Department dev;
    private Department ops;
    private User requester;
    private Project project;
    private Request request;

    @BeforeEach
    void setUp() {
        dev = em.persist(RepositoryTestSupport.department("개발팀"));
        ops = em.persist(RepositoryTestSupport.department("운영팀"));
        requester = em.persist(RepositoryTestSupport.user("EMP001", dev));
        project = em.persist(RepositoryTestSupport.project(dev));
        request = em.persist(RepositoryTestSupport.request(project.getId(), requester.getId()));
    }

    private AuditLog persistLog(String action, Department department, Request request, Network network,
                                Instant createdAt) {
        AuditLog log = em.persist(AuditLog.builder()
                .actor(requester).action(action).department(department).project(project).request(request)
                .taskIds(List.of()).network(network).ip("100.64.0.10").detail(Map.of())
                .build());
        em.flush();
        // createdAt은 저장 시각으로 채워지고 수정되지 않으므로 기간 검색 검증을 위해 직접 바꿈
        em.getEntityManager().createNativeQuery("update audit_logs set created_at = :at where id = :id")
                .setParameter("at", createdAt)
                .setParameter("id", log.getId())
                .executeUpdate();
        return log;
    }

    private List<AuditLog> findAll(UUID scopeDepartmentId, UUID scopeRequesterId) {
        return auditLogRepository.findPage(scopeDepartmentId, scopeRequesterId,
                null, null, null, null, null, null, MIN, MAX, Limit.of(100));
    }

    @Test
    void 범위가_없으면_요청이_없는_로그까지_최신순으로_조회한다() {
        AuditLog login = persistLog("LOGIN", null, null, Network.INTERNAL, Instant.parse("2026-11-01T00:00:00Z"));
        AuditLog submitted = persistLog("SUBMITTED", dev, request, Network.INTERNAL,
                Instant.parse("2026-11-02T00:00:00Z"));
        em.clear();

        assertThat(findAll(null, null)).extracting(AuditLog::getId).containsExactly(submitted.getId(), login.getId());
    }

    @Test
    void 부서_범위는_로그의_부서가_같은_것만_조회한다() {
        AuditLog devLog = persistLog("SUBMITTED", dev, request, Network.INTERNAL, Instant.now());
        persistLog("SUBMITTED", ops, request, Network.INTERNAL, Instant.now());
        persistLog("LOGIN", null, null, Network.INTERNAL, Instant.now());
        em.clear();

        assertThat(findAll(dev.getId(), null)).extracting(AuditLog::getId).containsExactly(devLog.getId());
    }

    @Test
    void 요청자_범위는_본인이_요청한_요청의_로그만_조회한다() {
        User other = em.persist(RepositoryTestSupport.user("EMP002", dev));
        Request otherRequest = em.persist(RepositoryTestSupport.request(project.getId(), other.getId()));
        AuditLog mine = persistLog("SUBMITTED", dev, request, Network.INTERNAL, Instant.now());
        persistLog("SUBMITTED", dev, otherRequest, Network.INTERNAL, Instant.now());
        persistLog("LOGIN", dev, null, Network.INTERNAL, Instant.now());
        em.clear();

        assertThat(findAll(null, requester.getId())).extracting(AuditLog::getId).containsExactly(mine.getId());
    }

    @Test
    void 검색_조건과_기간은_from_이상_to_미만으로_거른다() {
        persistLog("APPROVAL_SUBMITTED", dev, request, Network.EXTERNAL, Instant.parse("2026-09-30T23:59:59Z"));
        AuditLog inRange = persistLog("APPROVAL_SUBMITTED", dev, request, Network.EXTERNAL,
                Instant.parse("2026-10-01T00:00:00Z"));
        persistLog("APPROVAL_SUBMITTED", dev, request, Network.EXTERNAL, Instant.parse("2026-11-01T00:00:00Z"));
        persistLog("APPROVAL_SUBMITTED", dev, request, Network.INTERNAL, Instant.parse("2026-10-15T00:00:00Z"));
        persistLog("SUBMITTED", dev, request, Network.EXTERNAL, Instant.parse("2026-10-15T00:00:00Z"));
        em.clear();

        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        List<AuditLog> found = auditLogRepository.findPage(null, null, requester.getId(), dev.getId(),
                project.getId(), request.getId(), "APPROVAL_SUBMITTED", Network.EXTERNAL, from, to, Limit.of(100));

        assertThat(found).extracting(AuditLog::getId).containsExactly(inRange.getId());
        assertThat(auditLogRepository.countByConditions(null, null, requester.getId(), dev.getId(),
                project.getId(), request.getId(), "APPROVAL_SUBMITTED", Network.EXTERNAL, from, to)).isEqualTo(1);
    }

    @Test
    void 커서_다음_페이지는_마지막_항목보다_오래된_로그를_조회한다() {
        AuditLog first = persistLog("LOGIN", null, null, Network.INTERNAL, Instant.parse("2026-11-03T00:00:00Z"));
        AuditLog second = persistLog("LOGIN", null, null, Network.INTERNAL, Instant.parse("2026-11-02T00:00:00Z"));
        AuditLog third = persistLog("LOGIN", null, null, Network.INTERNAL, Instant.parse("2026-11-01T00:00:00Z"));
        em.clear();

        List<AuditLog> next = auditLogRepository.findPageAfter(null, null, null, null, null, null, null, null,
                MIN, MAX, Instant.parse("2026-11-03T00:00:00Z"), first.getId(), Limit.of(100));

        assertThat(next).extracting(AuditLog::getId).containsExactly(second.getId(), third.getId());
    }

    @Test
    void 내보내기용_조회는_행위자_부서_프로젝트를_함께_가져온다() {
        persistLog("SUBMITTED", dev, request, Network.INTERNAL, Instant.now());
        em.clear();

        List<AuditLog> found = auditLogRepository.findAllForExport(null, null, null, null, null, null, null, null,
                MIN, MAX);
        em.clear();

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getActor().getName()).isEqualTo("홍길동");
        assertThat(found.getFirst().getDepartment().getName()).isEqualTo("개발팀");
        assertThat(found.getFirst().getProject().getName()).isEqualTo("인증 서비스");
    }
}
