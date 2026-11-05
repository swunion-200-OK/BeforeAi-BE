package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Document;
import site.siyoung.beforeaibe.entity.DocumentVersion;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionEvent;
import site.siyoung.beforeaibe.entity.ExecutionLog;
import site.siyoung.beforeaibe.entity.ForbiddenProposal;
import site.siyoung.beforeaibe.entity.ForbiddenRule;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.PolicyVersion;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Rejection;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.SpecChangeRequest;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;
import site.siyoung.beforeaibe.entity.enums.ForbiddenProposalStatus;
import site.siyoung.beforeaibe.entity.enums.ForbiddenRuleStatus;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.PolicyPreset;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.SpecChangeRequestStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.ApprovalMatrixRule;
import site.siyoung.beforeaibe.entity.value.AuditExportFilters;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 리포지토리가 아직 없는 도메인 엔티티의 매핑을 실제 PostgreSQL에서 검증
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class DomainEntityMappingTest {

    @Autowired
    private TestEntityManager em;

    @Test
    void 프로젝트_담당자와_기획서_버전을_저장하고_담당자를_뺄_수_있다() {
        Department dept = em.persist(RepositoryTestSupport.department("개발팀"));
        User owner = em.persist(RepositoryTestSupport.user("EMP001"));
        User other = em.persist(RepositoryTestSupport.user("EMP002"));
        Project project = RepositoryTestSupport.project(dept);
        project.addOwner(owner);
        project.addOwner(other);
        em.persist(project);
        em.persist(SpecVersion.builder()
                .project(project).version(2).body("# 기획서").reason("수정").restoredFromVersion(1).createdBy(owner)
                .build());
        em.flush();
        em.clear();

        Project found = em.find(Project.class, project.getId());
        assertThat(found.getDepartment().getName()).isEqualTo("개발팀");
        assertThat(found.getRepository()).isEqualTo("git@internal:auth-service.git");
        assertThat(found.getOwners()).extracting(o -> o.getUser().getId())
                .containsExactlyInAnyOrder(owner.getId(), other.getId());

        found.removeOwner(other);
        em.flush();
        em.clear();

        assertThat(em.find(Project.class, project.getId()).getOwners()).hasSize(1);
        SpecVersion spec = em.getEntityManager()
                .createQuery("select s from SpecVersion s where s.project.id = :id", SpecVersion.class)
                .setParameter("id", project.getId())
                .getSingleResult();
        assertThat(spec.getVersion()).isEqualTo(2);
        assertThat(spec.getRestoredFromVersion()).isEqualTo(1);
        assertThat(spec.getCreatedBy().getId()).isEqualTo(owner.getId());
        assertThat(spec.getCreatedAt()).isNotNull();
    }

    @Test
    void 같은_프로젝트에서_기획서_버전이_겹치면_저장되지_않는다() {
        Department dept = em.persist(RepositoryTestSupport.department("개발팀"));
        User user = em.persist(RepositoryTestSupport.user("EMP001"));
        Project project = em.persist(RepositoryTestSupport.project(dept));
        em.persist(SpecVersion.builder().project(project).version(1).body("a").createdBy(user).build());

        assertThatThrownBy(() -> em.persistAndFlush(
                SpecVersion.builder().project(project).version(1).body("b").createdBy(user).build()))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void 기획서_수정_요청을_반려하면_사유가_남는다() {
        Department dept = em.persist(RepositoryTestSupport.department("개발팀"));
        User requester = em.persist(RepositoryTestSupport.user("EMP001"));
        Project project = em.persist(RepositoryTestSupport.project(dept));
        SpecChangeRequest cr = em.persist(SpecChangeRequest.builder()
                .project(project).requester(requester).content("다국어 안내").reason("다국어 지원")
                .status(SpecChangeRequestStatus.OPEN).build());
        cr.reject("범위 밖");
        em.flush();
        em.clear();

        SpecChangeRequest found = em.find(SpecChangeRequest.class, cr.getId());
        assertThat(found.getStatus()).isEqualTo(SpecChangeRequestStatus.REJECTED);
        assertThat(found.getRejectionReason()).isEqualTo("범위 밖");
        assertThat(found.getRequester().getId()).isEqualTo(requester.getId());
    }

    @Test
    void 금지_작업_제안을_확정하고_규칙은_해제_대기로_바꿀_수_있다() {
        Department dept = em.persist(RepositoryTestSupport.department("개발팀"));
        ForbiddenProposal proposal = em.persist(ForbiddenProposal.builder()
                .department(dept).taskType(TaskType.DEPLOY).target("production").reason("배포 동결")
                .status(ForbiddenProposalStatus.OPEN).build());
        ForbiddenRule rule = em.persist(ForbiddenRule.builder()
                .department(dept).taskType(TaskType.DEPLOY).target("production").reason("배포 동결")
                .status(ForbiddenRuleStatus.CONFIRMED).build());
        proposal.confirm();
        rule.requestRelease("동결 종료");
        em.flush();
        em.clear();

        assertThat(em.find(ForbiddenProposal.class, proposal.getId()).getStatus())
                .isEqualTo(ForbiddenProposalStatus.CONFIRMED);
        ForbiddenRule foundRule = em.find(ForbiddenRule.class, rule.getId());
        assertThat(foundRule.getStatus()).isEqualTo(ForbiddenRuleStatus.RELEASE_PENDING);
        assertThat(foundRule.getReleaseReason()).isEqualTo("동결 종료");
        assertThat(foundRule.getTaskType()).isEqualTo(TaskType.DEPLOY);
    }

    @Test
    void 정책_버전의_승인_기준표는_jsonb로_저장된다() {
        User admin = em.persist(RepositoryTestSupport.user("ADMIN001"));
        List<ApprovalMatrixRule> matrix = List.of(
                new ApprovalMatrixRule(TaskType.CODE_FEATURE, Risk.MEDIUM,
                        List.of(new RequiredApprover(ApproverRule.SAME_DEPT_OTHER, 1, null))),
                new ApprovalMatrixRule(TaskType.DEPLOY, Risk.HIGH,
                        List.of(new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, null))));
        PolicyVersion policy = em.persist(PolicyVersion.builder()
                .version(8).preset(PolicyPreset.MEDIUM).approvalMatrix(matrix).policyHash("sha256:abc")
                .adminKeyId(UUID.randomUUID()).adminSignature("c2ln").webshellApplied(false).createdBy(admin)
                .build());
        policy.markWebshellApplied();
        em.flush();
        em.clear();

        PolicyVersion found = em.find(PolicyVersion.class, policy.getId());
        assertThat(found.getApprovalMatrix()).isEqualTo(matrix);
        assertThat(found.getPreset()).isEqualTo(PolicyPreset.MEDIUM);
        assertThat(found.isWebshellApplied()).isTrue();
    }

    @Test
    void 실행_세션과_서명된_이벤트_로그를_저장하고_같은_이벤트는_한_번만_저장된다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = RepositoryTestSupport.plan(request);
        plan.addTask(RepositoryTestSupport.codeTask(1));
        em.persist(plan);
        Task task = plan.getTasks().getFirst();
        Execution execution = em.persist(Execution.builder()
                .request(request).plan(plan).webshellExecutionId(UUID.randomUUID()).planHash("sha256:4be1")
                .status(ExecutionStatus.RUNNING).webshellSignature("c2ln").build());
        UUID eventId = UUID.randomUUID();
        em.persist(ExecutionEvent.builder()
                .eventId(eventId).execution(execution).type("TASK_SUCCEEDED").task(task)
                .occurredAt(Instant.parse("2026-11-04T03:10:00Z"))
                .data(Map.of("status", "SUCCEEDED", "message", "diff 적용 완료")).webshellSignature("c2ln")
                .build());
        ExecutionLog log = em.persist(ExecutionLog.builder()
                .execution(execution).request(request).task(task).sequence(120)
                .loggedAt(Instant.parse("2026-11-04T03:10:00Z")).message("diff 적용 시작").webshellSignature("c2ln")
                .build());
        execution.kill("잘못된 브랜치");
        em.flush();
        em.clear();

        Execution foundExecution = em.find(Execution.class, execution.getId());
        assertThat(foundExecution.getStatus()).isEqualTo(ExecutionStatus.KILLED);
        assertThat(foundExecution.getKillReason()).isEqualTo("잘못된 브랜치");
        ExecutionLog foundLog = em.find(ExecutionLog.class, log.getId());
        assertThat(foundLog.getSequence()).isEqualTo(120);
        assertThat(foundLog.getTask().getId()).isEqualTo(task.getId());
        ExecutionEvent foundEvent = em.getEntityManager()
                .createQuery("select e from ExecutionEvent e where e.eventId = :id", ExecutionEvent.class)
                .setParameter("id", eventId)
                .getSingleResult();
        assertThat(foundEvent.getData()).containsEntry("message", "diff 적용 완료");

        Execution managed = em.find(Execution.class, execution.getId());
        assertThatThrownBy(() -> em.persistAndFlush(ExecutionEvent.builder()
                .eventId(eventId).execution(managed).type("TASK_SUCCEEDED")
                .occurredAt(Instant.now()).data(Map.of()).webshellSignature("c2ln").build()))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void 거부_기록과_문서_버전을_저장한다() {
        Department dept = em.persist(RepositoryTestSupport.department("개발팀"));
        User rejecter = em.persist(RepositoryTestSupport.user("EMP001"));
        Project project = em.persist(RepositoryTestSupport.project(dept));
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = em.persist(RepositoryTestSupport.plan(request));
        UUID taskId = UUID.randomUUID();
        Rejection rejection = em.persist(Rejection.builder()
                .request(request).plan(plan).rejecter(rejecter).taskIds(List.of(taskId))
                .reason("배포 일정 충돌").network(Network.EXTERNAL).build());
        Document document = em.persist(Document.builder()
                .project(project).title("운영 안내").path("docs/login.md").currentVersion(1).build());
        em.persist(DocumentVersion.builder().document(document).version(1).body("# v1").request(request).build());
        em.persist(DocumentVersion.builder().document(document).version(2).body("# v2").request(request).build());
        document.changeCurrentVersion(2);
        em.flush();
        em.clear();

        Rejection foundRejection = em.find(Rejection.class, rejection.getId());
        assertThat(foundRejection.getTaskIds()).containsExactly(taskId);
        assertThat(foundRejection.getNetwork()).isEqualTo(Network.EXTERNAL);
        assertThat(foundRejection.getCreatedAt()).isNotNull();
        assertThat(em.find(Document.class, document.getId()).getCurrentVersion()).isEqualTo(2);
        List<DocumentVersion> versions = em.getEntityManager()
                .createQuery("select v from DocumentVersion v where v.document.id = :id order by v.version",
                        DocumentVersion.class)
                .setParameter("id", document.getId())
                .getResultList();
        assertThat(versions).extracting(DocumentVersion::getBody).containsExactly("# v1", "# v2");
    }

    @Test
    void 감사_로그와_내보내기_조건을_저장한다() {
        Department dept = em.persist(RepositoryTestSupport.department("개발팀"));
        User actor = em.persist(RepositoryTestSupport.user("EMP001"));
        Request request = RepositoryTestSupport.persistRequest(em);
        UUID taskId = UUID.randomUUID();
        AuditLog byUser = em.persist(AuditLog.builder()
                .actor(actor).action("APPROVAL_SUBMITTED").department(dept).request(request)
                .taskIds(List.of(taskId)).network(Network.EXTERNAL).ip("203.0.113.10")
                .detail(Map.of()).build());
        // 웹쉘 이벤트처럼 행위자·접속 환경이 없는 기록
        AuditLog byWebshell = em.persist(AuditLog.builder()
                .action("VERIFICATION_FAILED").request(request).taskIds(List.of())
                .detail(Map.of("failedChecks", List.of("NONCE_ALREADY_USED"))).build());
        AuditExportFilters filters = new AuditExportFilters(null, dept.getId(), null, null, "APPROVAL_SUBMITTED",
                Network.EXTERNAL, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
        AuditExport export = em.persist(AuditExport.builder()
                .requester(actor).format(ExportFormat.XLSX).status(ExportStatus.PROCESSING).filters(filters)
                .rowCount(1520).build());
        export.markReady("audit-logs_20261001-20261101.xlsx", Instant.parse("2026-11-04T05:20:00Z"));
        em.flush();
        em.clear();

        AuditLog foundByUser = em.find(AuditLog.class, byUser.getId());
        assertThat(foundByUser.getActor().getId()).isEqualTo(actor.getId());
        assertThat(foundByUser.getTaskIds()).containsExactly(taskId);
        assertThat(foundByUser.getIp()).isEqualTo("203.0.113.10");
        AuditLog foundByWebshell = em.find(AuditLog.class, byWebshell.getId());
        assertThat(foundByWebshell.getActor()).isNull();
        assertThat(foundByWebshell.getNetwork()).isNull();
        assertThat(foundByWebshell.getDetail()).containsKey("failedChecks");
        AuditExport foundExport = em.find(AuditExport.class, export.getId());
        assertThat(foundExport.getFilters()).isEqualTo(filters);
        assertThat(foundExport.getStatus()).isEqualTo(ExportStatus.READY);
        assertThat(foundExport.getExpiresAt()).isEqualTo(Instant.parse("2026-11-04T05:20:00Z"));
    }
}
