package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// mock 호출 검증이 아니라 실제 커밋·롤백에서 audit_logs 행이 생기는지 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditWriterPersistenceTest {

    @Autowired
    private AuditWriter auditWriter;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Department department;
    private User actor;
    private Project project;
    private Request request;

    @BeforeEach
    void setUp() {
        department = departmentRepository.saveAndFlush(
                Department.builder().name("감사팀-" + UUID.randomUUID()).build());
        actor = userRepository.saveAndFlush(User.builder()
                .employeeNumber("AW" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hash")
                .name("홍길동")
                .role(Role.EMPLOYEE)
                .departmentId(department.getId())
                .active(true)
                .passwordChangeRequired(false)
                .build());
        project = projectRepository.saveAndFlush(Project.builder()
                .name("감사 프로젝트")
                .department(department)
                .repository("git@internal:audit.git")
                .build());
        request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(actor.getId())
                .prompt("감사 로그 저장 확인")
                .source(RequestSource.PROMPT)
                .status(RequestStatus.REVIEWING)
                .analysisAttempts(1)
                .build());
    }

    // 실제로 커밋한 fixture를 FK 역순으로 지운다. 비동기 기록이 늦게 끝나도 지워지도록 audit_logs를 먼저 지운다.
    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            jdbcTemplate.update("delete from audit_logs where request_id = ? or actor_id = ?",
                    request.getId(), actor.getId());
            jdbcTemplate.update("delete from requests where id = ?", request.getId());
            jdbcTemplate.update("delete from projects where id = ?", project.getId());
            jdbcTemplate.update("delete from users where id = ?", actor.getId());
            jdbcTemplate.update("delete from departments where id = ?", department.getId());
        });
    }

    private int countLogs() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where request_id = ?", Integer.class, request.getId());
        return count == null ? 0 : count;
    }

    private void recordInTransaction(UUID taskId, boolean commit) {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            auditWriter.recordAfterCommit(actor.getId(), "EXECUTION_STARTED", request.getId(), List.of(taskId),
                    null, null, Map.of("planId", "x", "failedChecks", List.of("a")));
            if (!commit) {
                transaction.setRollbackOnly();
            }
        });
    }

    @Test
    void 커밋하면_audit_logs에_행이_저장된다() {
        UUID taskId = UUID.randomUUID();

        recordInTransaction(taskId, true);

        await().atMost(Duration.ofSeconds(5)).until(() -> countLogs() == 1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select action, request_id, actor_id, task_ids::text as task_ids, detail::text as detail"
                        + " from audit_logs where request_id = ?", request.getId());
        assertThat(row.get("action")).isEqualTo("EXECUTION_STARTED");
        assertThat(row.get("request_id")).isEqualTo(request.getId());
        assertThat(row.get("actor_id")).isEqualTo(actor.getId());
        assertThat((String) row.get("task_ids")).contains(taskId.toString());
        assertThat((String) row.get("detail")).contains("\"planId\"", "\"x\"", "\"failedChecks\"", "\"a\"");
    }

    @Test
    void 롤백하면_audit_logs에_행이_생기지_않는다() throws Exception {
        recordInTransaction(UUID.randomUUID(), false);

        // 비동기 기록이 있었다면 들어왔을 시간만큼 기다린 뒤에도 없어야 한다
        Thread.sleep(1000);
        assertThat(countLogs()).isZero();
    }
}
