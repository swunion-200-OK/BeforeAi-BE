package site.siyoung.beforeaibe.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.ExecutionLog;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.ApprovalEnvelope;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ExecutionLogRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.PlanHashService;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RequestApiTest {

    private static final String PROMPT = "로그인 실패 시 에러 메시지를 바꾸고 스테이징에 배포해줘";

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
    private ApprovalRepository approvalRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionLogRepository executionLogRepository;

    @Autowired
    private PlanHashService planHashService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private WebshellClient webshellClient;

    private Project project;
    private User requester;
    private User candidate;

    // 같은 사번으로 여러 번 로그인하지 않도록 토큰을 기억해 둠
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        Department ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        candidate = saveUser(userRepository, passwordEncoder, "EMP101", Role.EMPLOYEE, ops.getId());
        saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        saveUser(userRepository, passwordEncoder, "MGR101", Role.DEPT_MANAGER, ops.getId());
        saveUser(userRepository, passwordEncoder, "ADMIN001", Role.ADMIN, null);

        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build());
    }

    private String token(String employeeNumber) throws Exception {
        String token = tokens.get(employeeNumber);
        if (token == null) {
            token = json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
            tokens.put(employeeNumber, token);
        }
        return bearer(token);
    }

    // 요청과 현재 Plan을 저장함. Plan과 요청이 서로를 가리키므로 요청을 먼저 저장하고 현재 Plan ID를 나중에 채움
    private Request saveRequest(RequestStatus status, Task... tasks) {
        Request request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt(PROMPT)
                .source(RequestSource.PROMPT)
                .status(status)
                .analysisAttempts(1)
                .build());
        if (tasks.length > 0) {
            Plan plan = savePlan(request, tasks);
            ReflectionTestUtils.setField(request, "currentPlanId", plan.getId());
            requestRepository.saveAndFlush(request);
        }
        return request;
    }

    private Plan savePlan(Request request, Task... tasks) {
        Plan plan = Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
        for (Task task : tasks) {
            plan.addTask(task);
        }
        return planRepository.saveAndFlush(plan);
    }

    private static Task codeTask(int order, TaskStatus status) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("로그인 실패 메시지 변경")
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(status)
                .build();
    }

    private static Task deployTask(int order, TaskStatus status, List<UUID> candidates) {
        return Task.builder()
                .order(order)
                .type(TaskType.DEPLOY)
                .risk(Risk.HIGH)
                .title("스테이징 배포")
                .target("staging")
                .content(new TaskContent(null, "./deploy.sh staging", null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, candidates)))
                .status(status)
                .build();
    }

    private static Task forbiddenTask(int order) {
        return Task.builder()
                .order(order)
                .type(TaskType.DB)
                .risk(Risk.FORBIDDEN)
                .title("운영 DB 테이블 삭제")
                .target("prod-db")
                .content(new TaskContent(null, null, "DROP TABLE users", null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ADMIN, 1, null)))
                .status(TaskStatus.BLOCKED)
                .build();
    }

    private Plan currentPlan(UUID requestId) {
        Request request = requestRepository.findById(requestId).orElseThrow();
        return planRepository.findWithTasksById(request.getCurrentPlanId()).orElseThrow();
    }

    // --- POST /requests ---

    @Test
    void 요청을_만들면_202와_ANALYZING_상태로_저장된다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"projectId\": \"%s\", \"prompt\": \"%s\"}".formatted(project.getId(), PROMPT)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andReturn();

        Request saved = requestRepository.findById(UUID.fromString(json(result, "$.id"))).orElseThrow();
        assertThat(saved.getRequesterId()).isEqualTo(requester.getId());
        assertThat(saved.getSource()).isEqualTo(RequestSource.PROMPT);
        assertThat(saved.getAnalysisAttempts()).isEqualTo(1);
        assertThat(saved.getCurrentPlanId()).isNull();
    }

    @Test
    void 외부망에서는_요청을_만들_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"projectId\": \"%s\", \"prompt\": \"%s\"}".formatted(project.getId(), PROMPT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 없는_프로젝트나_빈_프롬프트로는_요청을_만들_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"projectId\": \"%s\", \"prompt\": \"%s\"}".formatted(UUID.randomUUID(), PROMPT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"projectId\": \"%s\", \"prompt\": \" \"}".formatted(project.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // --- GET /requests ---

    @Test
    void 목록은_역할별_범위만_보여_준다() throws Exception {
        Request own = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));
        Request candidateOf = saveRequest(RequestStatus.PENDING_APPROVAL,
                deployTask(1, TaskStatus.PENDING, List.of(candidate.getId())));

        // 요청자: 본인 요청 둘 다
        mockMvc.perform(get("/api/v1/requests").with(internal()).header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].requester.id").value(requester.getId().toString()));
        // 다른 부서 직원: 승인 후보인 요청만
        mockMvc.perform(get("/api/v1/requests").with(internal()).header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(candidateOf.getId().toString()));
        // 같은 부서 다른 직원: 볼 수 있는 요청 없음
        mockMvc.perform(get("/api/v1/requests").with(internal()).header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(jsonPath("$.items.length()").value(0));
        // 프로젝트 담당 부서 관리자, 관리자는 전체
        mockMvc.perform(get("/api/v1/requests").with(internal()).header(HttpHeaders.AUTHORIZATION, token("MGR001")))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/v1/requests").with(internal()).header(HttpHeaders.AUTHORIZATION, token("MGR101")))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/requests").with(external()).header(HttpHeaders.AUTHORIZATION, token("ADMIN001"))
                        .param("projectId", project.getId().toString()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].id").value(own.getId().toString()));
    }

    @Test
    void 목록은_mine과_status로_거르고_커서로_이어서_조회한다() throws Exception {
        Request first = saveRequest(RequestStatus.REVIEWING);
        Request second = saveRequest(RequestStatus.REVIEWING);
        saveRequest(RequestStatus.CANCELED);

        MvcResult page = mockMvc.perform(get("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001"))
                        .param("status", "REVIEWING").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.getId().toString()))
                .andReturn();

        mockMvc.perform(get("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001"))
                        .param("status", "REVIEWING").param("limit", "1")
                        .param("cursor", json(page, "$.nextCursor")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // 관리자 본인이 만든 요청은 없음
        mockMvc.perform(get("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")).param("mine", "true"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/requests").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001")).param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    // --- GET /requests/{id} ---

    @Test
    void 상세는_현재_Plan과_작업과_승인_현황을_돌려준다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.APPROVED), deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());
        Approval approval = approvalRepository.saveAndFlush(Approval.builder()
                .request(request).plan(plan).approverId(requester.getId()).keyId(UUID.randomUUID())
                .planHash("sha256:4be1").taskIds(List.of(plan.getTasks().getFirst().getId()))
                .network(Network.INTERNAL)
                .envelope(ApprovalEnvelope.builder().alg("HPKE-X25519-AES256GCM").encapsulatedKey("ZW5j").ciphertext("Y2lwaGVy").build())
                .build());

        mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requester.name").value("홍길동"))
                .andExpect(jsonPath("$.currentPlan.id").value(plan.getId().toString()))
                .andExpect(jsonPath("$.currentPlan.tasks.length()").value(2))
                .andExpect(jsonPath("$.currentPlan.tasks[1].content.command").value("./deploy.sh staging"))
                .andExpect(jsonPath("$.currentPlan.tasks[1].content.diff").doesNotExist())
                .andExpect(jsonPath("$.approvals[0].id").value(approval.getId().toString()))
                .andExpect(jsonPath("$.approvals[0].envelope").doesNotExist())
                .andExpect(jsonPath("$.clarification").isEmpty());
    }

    @Test
    void 요청자도_승인_후보도_아닌_직원은_상세를_볼_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));

        mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isNotFound());
    }

    // --- POST /requests/{id}/analysis/retry ---

    @Test
    void 분석에_실패한_요청을_재시도하면_ANALYZING이고_시도_횟수가_는다() throws Exception {
        Request request = saveRequest(RequestStatus.ANALYSIS_FAILED);

        mockMvc.perform(post("/api/v1/requests/{id}/analysis/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"));

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getAnalysisAttempts()).isEqualTo(2);
    }

    @Test
    void 분석을_3회_하면_더_재시도할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.ANALYSIS_FAILED);
        // 최초 분석 1회를 포함해 3회
        ReflectionTestUtils.setField(request, "analysisAttempts", 3);
        requestRepository.saveAndFlush(request);

        mockMvc.perform(post("/api/v1/requests/{id}/analysis/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RETRY_LIMIT"));
    }

    @Test
    void 분석_실패가_아니면_분석을_재시도할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING);

        mockMvc.perform(post("/api/v1/requests/{id}/analysis/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // --- PATCH /requests/{id}/plan ---

    @Test
    void 작업을_삭제하면_그_작업을_뺀_새_Plan이_현재_Plan이_되고_ANALYZING이_된다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.APPROVED), deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan before = currentPlan(request.getId());
        UUID codeTaskId = before.getTasks().get(0).getId();
        UUID deployTaskId = before.getTasks().get(1).getId();

        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("""
                                {"basePlanId": "%s", "changes": [
                                  {"op": "UPDATE", "taskId": "%s", "instruction": "메시지를 한국어와 영어 둘 다 보여줘"},
                                  {"op": "DELETE", "taskId": "%s"}
                                ]}
                                """.formatted(before.getId(), codeTaskId, deployTaskId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"));

        Plan after = currentPlan(request.getId());
        assertThat(after.getId()).isNotEqualTo(before.getId());
        assertThat(after.getTasks()).hasSize(1);
        assertThat(after.getTasks().getFirst().getTitle()).isEqualTo("로그인 실패 메시지 변경");
        // 기존 승인 서명은 무효이므로 다시 승인 대기
        assertThat(after.getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.PENDING);
        // 지난 Plan은 그대로 남음
        assertThat(planRepository.findWithTasksById(before.getId()).orElseThrow().getTasks()).hasSize(2);
    }

    @Test
    void answer를_보내면_확인_질문이_비워지고_Plan은_그대로다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));
        request.askClarification("스테이징만 배포할까요?");
        requestRepository.saveAndFlush(request);
        UUID planId = request.getCurrentPlanId();

        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"basePlanId\": \"%s\", \"answer\": \"네\"}".formatted(planId)))
                .andExpect(status().isAccepted());

        Request found = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(found.getClarification()).isNull();
        assertThat(found.getCurrentPlanId()).isEqualTo(planId);
        assertThat(found.getStatus()).isEqualTo(RequestStatus.ANALYZING);
    }

    @Test
    void basePlanId가_현재_Plan과_다르면_Plan을_수정할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));
        UUID taskId = currentPlan(request.getId()).getTasks().getFirst().getId();

        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"basePlanId\": \"%s\", \"changes\": [{\"op\": \"DELETE\", \"taskId\": \"%s\"}]}"
                                .formatted(UUID.randomUUID(), taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 요청자가_아니면_Plan을_수정할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));

        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001"))
                        .contentType("application/json")
                        .content("{\"basePlanId\": \"%s\", \"answer\": \"네\"}".formatted(request.getCurrentPlanId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 수정할_내용이_없거나_현재_Plan에_없는_작업이면_400이다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));

        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"basePlanId\": \"%s\"}".formatted(request.getCurrentPlanId())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"basePlanId\": \"%s\", \"changes\": [{\"op\": \"DELETE\", \"taskId\": \"%s\"}]}"
                                .formatted(request.getCurrentPlanId(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // --- POST /requests/{id}/submit ---

    @Test
    void 제출하면_Plan_해시를_발급하고_PENDING_APPROVAL이_된다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING,
                codeTask(1, TaskStatus.PENDING), deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())),
                forbiddenTask(3));

        MvcResult result = mockMvc.perform(post("/api/v1/requests/{id}/submit", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.planId").value(request.getCurrentPlanId().toString()))
                .andReturn();

        Plan plan = currentPlan(request.getId());
        Request found = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(plan.getPlanHash()).startsWith("sha256:").isEqualTo(planHashService.hash(found, plan));
        assertThat(json(result, "$.planHash")).isEqualTo(plan.getPlanHash());
        // ANYONE 작업만 본인 승인 가능. 후보에 본인이 없는 배포·금지 작업은 빠짐
        List<String> selfApprovable = JsonPath.read(result.getResponse().getContentAsString(),
                "$.selfApprovableTaskIds");
        assertThat(selfApprovable).containsExactly(plan.getTasks().getFirst().getId().toString());
    }

    @Test
    void 후보_목록에_본인이_있으면_본인_승인_가능한_작업이다() throws Exception {
        Request request = saveRequest(RequestStatus.VERIFICATION_FAILED,
                deployTask(1, TaskStatus.PENDING, List.of(requester.getId())));

        mockMvc.perform(post("/api/v1/requests/{id}/submit", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfApprovableTaskIds.length()").value(1));
    }

    @Test
    void 금지_작업만_남으면_제출할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, forbiddenTask(1));

        mockMvc.perform(post("/api/v1/requests/{id}/submit", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_TASK"));
    }

    @Test
    void 승인_대기_중에는_다시_제출할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));

        mockMvc.perform(post("/api/v1/requests/{id}/submit", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // --- POST /requests/{id}/cancel ---

    @Test
    void 외부망에서도_요청자는_요청을_취소할_수_있다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));

        mockMvc.perform(post("/api/v1/requests/{id}/cancel", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        Request found = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(RequestStatus.CANCELED);
        assertThat(found.getProgressVersion()).isEqualTo(1);
    }

    @Test
    void 실행_중인_요청은_취소할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));

        mockMvc.perform(post("/api/v1/requests/{id}/cancel", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // --- POST /requests/{id}/kill ---

    private Execution saveExecution(Request request, ExecutionStatus status) {
        return executionRepository.saveAndFlush(Execution.builder()
                .request(request)
                .plan(planRepository.findById(request.getCurrentPlanId()).orElseThrow())
                .webshellExecutionId(UUID.randomUUID())
                .planHash("sha256:4be1")
                .status(status)
                .webshellSignature("c2ln")
                .build());
    }

    @Test
    void 킬_스위치는_실행_중이던_작업을_FAILED로_시작_전_작업을_SKIPPED로_바꾼다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING,
                codeTask(1, TaskStatus.SUCCEEDED), codeTask(2, TaskStatus.RUNNING),
                deployTask(3, TaskStatus.APPROVED, List.of(candidate.getId())));
        Execution execution = saveExecution(request, ExecutionStatus.RUNNING);
        given(webshellClient.kill(eq(execution.getWebshellExecutionId()), any()))
                .willReturn(new WebshellClient.KillResult(execution.getWebshellExecutionId(), "KILLED", "c2ln"));
        UUID deployTaskId = currentPlan(request.getId()).getTasks().get(2).getId();

        mockMvc.perform(post("/api/v1/requests/{id}/kill", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("{\"reason\": \"중단\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedTaskIds.length()").value(1))
                .andExpect(jsonPath("$.skippedTaskIds[0]").value(deployTaskId.toString()));

        assertThat(currentPlan(request.getId()).getTasks()).extracting(Task::getStatus)
                .containsExactly(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.SKIPPED);
    }

    @Test
    void 킬_스위치는_웹쉘_실행을_중단하고_시작하지_않은_작업을_SKIPPED로_바꾼다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING,
                codeTask(1, TaskStatus.SUCCEEDED), deployTask(2, TaskStatus.APPROVED, List.of(candidate.getId())));
        Execution execution = saveExecution(request, ExecutionStatus.RUNNING);
        given(webshellClient.kill(eq(execution.getWebshellExecutionId()), any()))
                .willReturn(new WebshellClient.KillResult(execution.getWebshellExecutionId(), "KILLED", "c2ln"));
        UUID deployTaskId = currentPlan(request.getId()).getTasks().get(1).getId();

        // 외부망에서도, 요청을 볼 수 있는 승인 후보도 쓸 수 있음
        mockMvc.perform(post("/api/v1/requests/{id}/kill", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("{\"reason\": \"잘못된 브랜치에 배포 중\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KILLED"))
                .andExpect(jsonPath("$.skippedTaskIds.length()").value(1))
                .andExpect(jsonPath("$.skippedTaskIds[0]").value(deployTaskId.toString()));

        then(webshellClient).should().kill(execution.getWebshellExecutionId(), "잘못된 브랜치에 배포 중");
        Execution found = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.KILLED);
        assertThat(found.getKillReason()).isEqualTo("잘못된 브랜치에 배포 중");
        assertThat(currentPlan(request.getId()).getTasks()).extracting(Task::getStatus)
                .containsExactly(TaskStatus.SUCCEEDED, TaskStatus.SKIPPED);
    }

    @Test
    void 웹쉘에_연결할_수_없으면_502이고_요청은_실행_중으로_남는다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));
        saveExecution(request, ExecutionStatus.RUNNING);
        given(webshellClient.kill(any(), any())).willThrow(new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/requests/{id}/kill", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"reason\": \"중단\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"));

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.RUNNING);
    }

    @Test
    void 실행_중이_아니거나_볼_수_없는_요청은_중단할_수_없다() throws Exception {
        Request completed = saveRequest(RequestStatus.COMPLETED, codeTask(1, TaskStatus.SUCCEEDED));
        mockMvc.perform(post("/api/v1/requests/{id}/kill", completed.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        Request running = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));
        mockMvc.perform(post("/api/v1/requests/{id}/kill", running.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden());
        then(webshellClient).should(never()).kill(any(), any());
    }

    // --- POST /requests/{id}/retry ---

    @Test
    void 부분_완료_요청을_재시도하면_실패와_건너뛴_작업으로_새_Plan과_해시를_만든다() throws Exception {
        Request request = saveRequest(RequestStatus.PARTIALLY_COMPLETED,
                codeTask(1, TaskStatus.SUCCEEDED), codeTask(2, TaskStatus.FAILED),
                deployTask(3, TaskStatus.SKIPPED, List.of(candidate.getId())));
        UUID previousPlanId = request.getCurrentPlanId();

        MvcResult result = mockMvc.perform(post("/api/v1/requests/{id}/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();

        Plan retryPlan = currentPlan(request.getId());
        assertThat(retryPlan.getId()).isNotEqualTo(previousPlanId);
        assertThat(json(result, "$.planId")).isEqualTo(retryPlan.getId().toString());
        assertThat(json(result, "$.planHash")).isEqualTo(retryPlan.getPlanHash()).startsWith("sha256:");
        assertThat(retryPlan.getBaseCommit()).isEqualTo("9b2f4e1");
        assertThat(retryPlan.getTasks()).extracting(Task::getOrder).containsExactly(2, 3);
        assertThat(retryPlan.getTasks()).extracting(Task::getStatus)
                .containsOnly(TaskStatus.PENDING);
    }

    @Test
    void 부분_완료나_실패가_아니면_실패_작업을_재시도할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, codeTask(1, TaskStatus.SUCCEEDED));

        mockMvc.perform(post("/api/v1/requests/{id}/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // --- GET /requests/{id}/logs ---

    @Test
    void 실행_로그는_웹쉘_서명과_함께_순서대로_페이지로_내려온다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));
        Execution execution = saveExecution(request, ExecutionStatus.RUNNING);
        Task task = currentPlan(request.getId()).getTasks().getFirst();
        for (long sequence = 1; sequence <= 3; sequence++) {
            executionLogRepository.saveAndFlush(ExecutionLog.builder()
                    .execution(execution).request(request).task(task).sequence(sequence)
                    .loggedAt(Instant.parse("2026-11-04T03:10:00Z")).message("로그 " + sequence)
                    .webshellSignature("sig-" + sequence).build());
        }

        MvcResult page = mockMvc.perform(get("/api/v1/requests/{id}/logs", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].sequence").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(task.getId().toString()))
                .andExpect(jsonPath("$.items[0].webshellSignature").value("sig-1"))
                .andReturn();

        mockMvc.perform(get("/api/v1/requests/{id}/logs", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")).param("limit", "2")
                        .param("cursor", json(page, "$.nextCursor")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].message").value("로그 3"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // --- GET /requests/{id}/progress ---

    @Test
    void 진행_트리는_현재_Plan을_펼치고_지난_Plan은_접어서_보여_준다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.APPROVED), deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan oldPlan = currentPlan(request.getId());
        Plan current = savePlan(request,
                codeTask(1, TaskStatus.APPROVED), deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        ReflectionTestUtils.setField(request, "currentPlanId", current.getId());
        requestRepository.saveAndFlush(request);
        UUID codeTaskId = current.getTasks().get(0).getId();
        UUID deployTaskId = current.getTasks().get(1).getId();
        Approval approval = approvalRepository.saveAndFlush(Approval.builder()
                .request(request).plan(current).approverId(requester.getId()).keyId(UUID.randomUUID())
                .planHash("sha256:4be1").taskIds(List.of(codeTaskId)).network(Network.INTERNAL)
                .envelope(ApprovalEnvelope.builder().alg("HPKE-X25519-AES256GCM").encapsulatedKey("ZW5j").ciphertext("Y2lwaGVy").build())
                .build());

        mockMvc.perform(get("/api/v1/requests/{id}/progress", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.root.id").value("request:" + request.getId()))
                .andExpect(jsonPath("$.root.type").value("REQUEST"))
                .andExpect(jsonPath("$.root.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.root.children[0].id").value("plan:" + current.getId()))
                .andExpect(jsonPath("$.root.children[0].label").value("Plan v2"))
                .andExpect(jsonPath("$.root.children[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.root.children[0].children[0].id").value("task:" + codeTaskId))
                .andExpect(jsonPath("$.root.children[0].children[0].label").value("1. 로그인 실패 메시지 변경"))
                .andExpect(jsonPath("$.root.children[0].children[0].dependsOn.length()").value(0))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].id")
                        .value("approval:" + approval.getId()))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].label").value("홍길동"))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].network").value("INTERNAL"))
                .andExpect(jsonPath("$.root.children[0].children[1].dependsOn[0]").value("task:" + codeTaskId))
                .andExpect(jsonPath("$.root.children[0].children[1].id").value("task:" + deployTaskId))
                .andExpect(jsonPath("$.root.children[0].children[1].children[0].status").value("PENDING"))
                .andExpect(jsonPath("$.root.children[0].children[1].children[0].candidates[0]")
                        .value(candidate.getId().toString()))
                .andExpect(jsonPath("$.root.children[0].children[1].children[0].label").doesNotExist())
                .andExpect(jsonPath("$.root.children[1].id").value("plan:" + oldPlan.getId()))
                .andExpect(jsonPath("$.root.children[1].label").value("Plan v1"))
                .andExpect(jsonPath("$.root.children[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.root.children[1].collapsed").value(true))
                .andExpect(jsonPath("$.root.children[1].children.length()").value(0));
    }

    @Test
    void 진행_트리_스트림에_지난_Last_Event_ID로_다시_붙으면_tree_replaced를_받는다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));
        ReflectionTestUtils.setField(request, "progressVersion", 3L);
        requestRepository.saveAndFlush(request);

        MvcResult result = mockMvc.perform(get("/api/v1/requests/{id}/progress/stream", request.getId())
                        .with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(result.getResponse().getContentAsString())
                .contains("id:3")
                .contains("event:tree.replaced");
    }

    // EventSource는 Accept: text/event-stream으로 요청하므로 오류 본문(JSON)도 그대로 내려가야 함
    @Test
    void SSE_구독_권한이_없으면_text_event_stream_요청이어도_403_오류_본문을_받는다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));

        for (String path : List.of("/api/v1/requests/{id}/progress/stream", "/api/v1/requests/{id}/logs/stream")) {
            mockMvc.perform(get(path, request.getId()).with(internal())
                            .header(HttpHeaders.AUTHORIZATION, token("EMP002"))
                            .header(HttpHeaders.ACCEPT, "text/event-stream"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        }
    }

    @Test
    void 볼_수_없는_요청의_스트림은_구독할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, codeTask(1, TaskStatus.RUNNING));

        mockMvc.perform(get("/api/v1/requests/{id}/logs", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/requests/{id}/progress", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 분석에_실패한_요청은_취소할_수_있다() throws Exception {
        Request request = saveRequest(RequestStatus.ANALYSIS_FAILED, codeTask(1, TaskStatus.PENDING));

        mockMvc.perform(post("/api/v1/requests/{id}/cancel", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void 전체_실패한_요청을_재시도하면_성공하지_않은_작업으로_새_Plan을_만든다() throws Exception {
        Request request = saveRequest(RequestStatus.FAILED,
                codeTask(1, TaskStatus.FAILED), codeTask(2, TaskStatus.APPROVED), codeTask(3, TaskStatus.SUCCEEDED));

        mockMvc.perform(post("/api/v1/requests/{id}/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        assertThat(currentPlan(request.getId()).getTasks()).hasSize(2)
                .allMatch(task -> task.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void UPDATE_지시와_답변은_재분석_입력으로_보관된다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/requests/{id}/plan", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("""
                                {"basePlanId": "%s", "answer": "스테이징입니다",
                                 "changes": [{"op": "UPDATE", "taskId": "%s", "instruction": "문구를 바꿔 주세요"}]}"""
                                .formatted(plan.getId(), plan.getTasks().getFirst().getId())))
                .andExpect(status().isAccepted());

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getReanalysisInput())
                .contains("스테이징입니다", "문구를 바꿔 주세요", plan.getTasks().getFirst().getTitle());
    }

    @Test
    void 상세는_직전_Plan과_달라진_작업과_빠진_작업을_돌려준다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, task("유지", "a", "same"), task("변경", "b", "before"), task("삭제", "c", "x"));
        Plan revised = savePlan(request, task("유지", "a", "same"), task("변경", "b", "after"));
        org.springframework.test.util.ReflectionTestUtils.setField(request, "currentPlanId", revised.getId());
        requestRepository.saveAndFlush(request);
        UUID changedId = currentPlan(request.getId()).getTasks().stream()
                .filter(task -> task.getTitle().equals("변경")).findFirst().orElseThrow().getId();

        mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(jsonPath("$.changedTaskIds.length()").value(1))
                .andExpect(jsonPath("$.changedTaskIds[0]").value(changedId.toString()))
                .andExpect(jsonPath("$.removedTaskTitles[0]").value("삭제"));
    }

    private static Task task(String title, String target, String diff) {
        return Task.builder().order(title.hashCode() & 0xffff).type(TaskType.CODE_FEATURE).risk(Risk.MEDIUM).title(title)
                .target(target).content(new TaskContent(diff, null, null, null)).relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.PENDING).build();
    }

    @Test
    void 부분_완료_재시도는_성공_이벤트가_유실된_작업을_다시_실행하지_않는다() throws Exception {
        Request request = saveRequest(RequestStatus.PARTIALLY_COMPLETED,
                codeTask(1, TaskStatus.FAILED), codeTask(2, TaskStatus.APPROVED), codeTask(3, TaskStatus.RUNNING));

        mockMvc.perform(post("/api/v1/requests/{id}/retry", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk());

        assertThat(currentPlan(request.getId()).getTasks()).hasSize(1);
    }

    @Test
    void 승인_후보가_부족한_작업이_있으면_제출할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, deployTask(1, TaskStatus.PENDING, List.of()));

        mockMvc.perform(post("/api/v1/requests/{id}/submit", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }
}
