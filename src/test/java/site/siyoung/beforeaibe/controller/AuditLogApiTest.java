package site.siyoung.beforeaibe.controller;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditLogApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department ops;
    private Request myRequest;
    private AuditLog myRequestLog;
    private AuditLog otherRequestLog;
    private AuditLog opsLoginLog;

    private final Map<String, String> tokens = new HashMap<>();

    // 개발팀: EMP001의 요청 로그, EMP002의 요청 로그 / 운영팀: 요청 없는 관리자 로그인 로그
    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        User me = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        User other = saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        User admin = saveUser(userRepository, passwordEncoder, "ADMIN001", Role.ADMIN, null);
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스").department(dev).repository("git@internal:auth-service.git").build());
        myRequest = saveRequest(project, me);
        Request otherRequest = saveRequest(project, other);

        myRequestLog = saveLog(me, "APPROVAL_SUBMITTED", dev, project, myRequest, Network.EXTERNAL,
                Instant.parse("2026-11-03T00:00:00Z"));
        otherRequestLog = saveLog(other, "SUBMITTED", dev, project, otherRequest, Network.INTERNAL,
                Instant.parse("2026-11-02T00:00:00Z"));
        opsLoginLog = saveLog(admin, "LOGIN", ops, null, null, Network.INTERNAL,
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    private String token(String employeeNumber) throws Exception {
        String token = tokens.get(employeeNumber);
        if (token == null) {
            token = json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
            tokens.put(employeeNumber, token);
        }
        return bearer(token);
    }

    private Request saveRequest(Project project, User requester) {
        return requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt("작업 요청")
                .source(RequestSource.PROMPT)
                .status(RequestStatus.REVIEWING)
                .analysisAttempts(1)
                .build());
    }

    // createdAt은 저장 시각으로 채워지고 수정되지 않으므로 정렬·기간 검증을 위해 직접 바꿈
    private AuditLog saveLog(User actor, String action, Department department, Project project, Request request,
                             Network network, Instant createdAt) {
        AuditLog log = auditLogRepository.saveAndFlush(AuditLog.builder()
                .actor(actor).action(action).department(department).project(project).request(request)
                .taskIds(List.of(UUID.randomUUID())).network(network).ip("100.64.0.10")
                .detail(Map.of("failedChecks", List.of("NONCE_ALREADY_USED")))
                .build());
        em.createNativeQuery("update audit_logs set created_at = :at where id = :id")
                .setParameter("at", createdAt)
                .setParameter("id", log.getId())
                .executeUpdate();
        em.clear();
        return log;
    }

    @Test
    void 관리자는_전체_감사_로그를_최신순으로_본다() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").with(external()).header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(myRequestLog.getId().toString()))
                .andExpect(jsonPath("$.items[0].at").value("2026-11-03T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].actor.name").value("홍길동"))
                .andExpect(jsonPath("$.items[0].action").value("APPROVAL_SUBMITTED"))
                .andExpect(jsonPath("$.items[0].target.requestId").value(myRequest.getId().toString()))
                .andExpect(jsonPath("$.items[0].target.taskIds.length()").value(1))
                .andExpect(jsonPath("$.items[0].network").value("EXTERNAL"))
                .andExpect(jsonPath("$.items[0].ip").value("100.64.0.10"))
                .andExpect(jsonPath("$.items[2].id").value(opsLoginLog.getId().toString()))
                .andExpect(jsonPath("$.items[2].target.requestId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 부서_관리자는_소속_부서_로그만_보고_다른_부서_조건은_결과가_좁혀진다() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(myRequestLog.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(otherRequestLog.getId().toString()));
        mockMvc.perform(get("/api/v1/audit-logs").param("departmentId", ops.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 직원은_본인이_요청한_요청의_로그만_본다() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(myRequestLog.getId().toString()));
    }

    @Test
    void 검색_조건으로_거른다() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("action", "APPROVAL_SUBMITTED")
                        .param("network", "EXTERNAL")
                        .param("from", "2026-11-03T00:00:00Z")
                        .param("to", "2026-11-04T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(myRequestLog.getId().toString()));
    }

    @Test
    void 커서로_다음_페이지를_조회한다() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/audit-logs").param("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();

        mockMvc.perform(get("/api/v1/audit-logs").param("limit", "2").param("cursor", json(first, "$.nextCursor"))
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(opsLoginLog.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void from이_to보다_늦거나_형식이_잘못되면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("from", "2026-11-04T00:00:00Z").param("to", "2026-11-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/audit-logs").param("network", "VPN")
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 범위_안의_로그는_상세를_보고_범위_밖은_403_없으면_404다() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs/{id}", myRequestLog.getId())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.failedChecks[0]").value("NONCE_ALREADY_USED"));
        mockMvc.perform(get("/api/v1/audit-logs/{id}", otherRequestLog.getId())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/v1/audit-logs/{id}", opsLoginLog.getId())
                        .header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-logs/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
