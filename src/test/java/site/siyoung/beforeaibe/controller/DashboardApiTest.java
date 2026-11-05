package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

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
class DashboardApiTest {

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
    private PlanRepository planRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department ops;
    private User requester;
    private User candidate;
    private Project devProject;

    private final Map<String, String> tokens = new HashMap<>();

    // 개발팀 프로젝트: EMP001의 승인 대기 요청(누구나 승인하는 MEDIUM 코드 작업 + EMP101이 후보인 HIGH 배포 작업)
    // 운영팀 프로젝트: EMP101의 완료 요청(LOW 문서 작업)
    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        candidate = saveUser(userRepository, passwordEncoder, "EMP101", Role.EMPLOYEE, ops.getId());
        saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        saveUser(userRepository, passwordEncoder, "ADMIN001", Role.ADMIN, null);

        devProject = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스").department(dev).repository("git@internal:auth-service.git").build());
        Project opsProject = projectRepository.saveAndFlush(Project.builder()
                .name("운영 도구").department(ops).repository("git@internal:ops-tools.git").build());

        saveRequest(devProject, requester, RequestStatus.PENDING_APPROVAL,
                task(1, TaskType.CODE_FEATURE, Risk.MEDIUM, null),
                task(2, TaskType.DEPLOY, Risk.HIGH, List.of(candidate.getId())));
        saveRequest(opsProject, candidate, RequestStatus.COMPLETED, task(1, TaskType.DOC, Risk.LOW, null));
    }

    private String token(String employeeNumber) throws Exception {
        String token = tokens.get(employeeNumber);
        if (token == null) {
            token = json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
            tokens.put(employeeNumber, token);
        }
        return bearer(token);
    }

    private Request saveRequest(Project project, User requester, RequestStatus status, Task... tasks) {
        Request request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt("작업 요청")
                .source(RequestSource.PROMPT)
                .status(status)
                .analysisAttempts(1)
                .build());
        Plan plan = Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository(project.getRepository())
                .baseCommit("9b2f4e1")
                .build();
        for (Task task : tasks) {
            plan.addTask(task);
        }
        plan = planRepository.saveAndFlush(plan);
        ReflectionTestUtils.setField(request, "currentPlanId", plan.getId());
        return requestRepository.saveAndFlush(request);
    }

    private static Task task(int order, TaskType type, Risk risk, List<UUID> candidates) {
        return task(order, type, risk, candidates, TaskStatus.PENDING);
    }

    // candidates가 null이면 누구나 승인할 수 있는(ANYONE) 작업
    private static Task task(int order, TaskType type, Risk risk, List<UUID> candidates, TaskStatus status) {
        return Task.builder()
                .order(order)
                .type(type)
                .risk(risk)
                .title("작업 " + order)
                .target("target")
                .content(new TaskContent(null, "echo ok", null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(
                        candidates == null ? ApproverRule.ANYONE : ApproverRule.PROJECT_OWNER, 1, candidates)))
                .status(status)
                .build();
    }

    @Test
    void 직원은_기본으로_본인_요청만_집계하고_0건인_항목도_담는다() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").with(external()).header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ME"))
                .andExpect(jsonPath("$.requestCount").value(1))
                .andExpect(jsonPath("$.statusCounts.PENDING_APPROVAL").value(1))
                .andExpect(jsonPath("$.statusCounts.COMPLETED").value(0))
                .andExpect(jsonPath("$.statusCounts.ANALYZING").value(0))
                .andExpect(jsonPath("$.riskCounts.LOW").value(0))
                .andExpect(jsonPath("$.riskCounts.MEDIUM").value(1))
                .andExpect(jsonPath("$.riskCounts.HIGH").value(1))
                .andExpect(jsonPath("$.riskCounts.FORBIDDEN").value(0))
                // 승인 대기 건수는 범위와 상관없이 내가 승인할 수 있는 작업 수(ANYONE 코드 작업 1건)
                .andExpect(jsonPath("$.pendingApprovalCount").value(1));
    }

    @Test
    void 직원이_전체나_부서_범위를_요청하면_403이다() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").param("scope", "COMPANY")
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/v1/dashboard").param("scope", "DEPARTMENT")
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 부서_관리자는_기본으로_소속_부서를_집계하고_다른_부서나_전체는_403이다() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("DEPARTMENT"))
                .andExpect(jsonPath("$.requestCount").value(1))
                .andExpect(jsonPath("$.statusCounts.COMPLETED").value(0));
        mockMvc.perform(get("/api/v1/dashboard").param("scope", "DEPARTMENT").param("departmentId", ops.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/v1/dashboard").param("scope", "COMPANY")
                        .header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_기본으로_전체를_집계하고_부서를_지정해_볼_수_있다() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("COMPANY"))
                .andExpect(jsonPath("$.requestCount").value(2))
                .andExpect(jsonPath("$.statusCounts.COMPLETED").value(1))
                .andExpect(jsonPath("$.riskCounts.LOW").value(1));
        mockMvc.perform(get("/api/v1/dashboard").param("scope", "DEPARTMENT").param("departmentId", ops.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("DEPARTMENT"))
                .andExpect(jsonPath("$.requestCount").value(1))
                .andExpect(jsonPath("$.statusCounts.COMPLETED").value(1));
    }

    @Test
    void 관리자가_부서_범위에_departmentId를_빼면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").param("scope", "DEPARTMENT")
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 승인_대기_건수는_3_6_내_승인_대기_목록의_작업_수와_같다() throws Exception {
        // 재승인 필요 요청도 승인 받을 수 있는 상태이므로 셈
        saveRequest(devProject, requester, RequestStatus.REAPPROVAL_REQUIRED,
                task(1, TaskType.DEPLOY, Risk.HIGH, List.of(candidate.getId())));
        // 이미 승인된 작업, 다른 사람만 후보인 작업, 승인 받을 수 없는 상태의 요청은 세지 않음
        saveRequest(devProject, requester, RequestStatus.PENDING_APPROVAL,
                task(1, TaskType.DEPLOY, Risk.HIGH, List.of(candidate.getId()), TaskStatus.APPROVED),
                task(2, TaskType.DEPLOY, Risk.HIGH, List.of(requester.getId())));
        saveRequest(devProject, requester, RequestStatus.REVIEWING,
                task(1, TaskType.DEPLOY, Risk.HIGH, List.of(candidate.getId())));

        // 기존 승인 대기 요청의 ANYONE 작업·후보 작업 2건 + 재승인 필요 요청 1건
        mockMvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingApprovalCount").value(3));
        mockMvc.perform(get("/api/v1/approvals/pending").header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }
}
