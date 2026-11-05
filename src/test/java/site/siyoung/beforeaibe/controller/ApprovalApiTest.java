package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.ApprovalRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.PlanHashService;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class ApprovalApiTest {

    private static final String PROMPT = "로그인 실패 시 에러 메시지를 바꾸고 스테이징에 배포해줘";
    private static final String ENVELOPE = """
            {"alg": "HPKE-X25519-AES256GCM", "encapsulatedKey": "ZW5j", "ciphertext": "Y2lwaGVy"}""";

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
    private SigningKeyRepository signingKeyRepository;

    @Autowired
    private PlanHashService planHashService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private WebshellClient webshellClient;

    private Project project;
    private User requester;
    private User candidate;
    private User other;
    private UUID devDepartmentId;
    private UUID opsDepartmentId;

    @BeforeEach
    void setUp() {
        var dev = departmentRepository.saveAndFlush(
                site.siyoung.beforeaibe.entity.Department.builder().name("개발팀").build());
        var ops = departmentRepository.saveAndFlush(
                site.siyoung.beforeaibe.entity.Department.builder().name("운영팀").build());
        devDepartmentId = dev.getId();
        opsDepartmentId = ops.getId();
        requester = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        other = saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        candidate = saveUser(userRepository, passwordEncoder, "EMP101", Role.EMPLOYEE, ops.getId());

        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build());
    }

    // HTTP 호출을 테스트 트랜잭션으로 감싸지 않아 승인 저장 커밋과 실행 트랜잭션을 실제로 검증한다.
    // saveAndFlush로 커밋한 이 테스트의 fixture만 FK 역순으로 정리한다.
    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            if (project != null) {
                UUID projectId = project.getId();
                jdbcTemplate.update("delete from executions where request_id in (select id from requests where project_id = ?)", projectId);
                jdbcTemplate.update("delete from approvals where request_id in (select id from requests where project_id = ?)", projectId);
                jdbcTemplate.update("update requests set current_plan_id = null where project_id = ?", projectId);
                jdbcTemplate.update("delete from tasks where plan_id in (select p.id from plans p join requests r on r.id = p.request_id where r.project_id = ?)", projectId);
                jdbcTemplate.update("delete from plans where request_id in (select id from requests where project_id = ?)", projectId);
                jdbcTemplate.update("delete from requests where project_id = ?", projectId);
                jdbcTemplate.update("delete from projects where id = ?", projectId);
            }
            for (User user : new User[]{requester, other, candidate}) {
                if (user != null) {
                    jdbcTemplate.update("delete from refresh_tokens where user_id = ?", user.getId());
                    jdbcTemplate.update("delete from signing_keys where user_id = ?", user.getId());
                    jdbcTemplate.update("delete from users where id = ?", user.getId());
                }
            }
            jdbcTemplate.update("delete from departments where id in (?, ?)", devDepartmentId, opsDepartmentId);
        });
    }

    private String token(String employeeNumber) throws Exception {
        return bearer(json(loginInternal(mockMvc, employeeNumber), "$.accessToken"));
    }

    private Request saveRequest(RequestStatus status, Task... tasks) {
        Request request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt(PROMPT)
                .source(RequestSource.PROMPT)
                .status(status)
                .analysisAttempts(1)
                .build());
        Plan plan = Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
        for (Task task : tasks) {
            plan.addTask(task);
        }
        Plan saved = planRepository.saveAndFlush(plan);
        ReflectionTestUtils.setField(request, "currentPlanId", saved.getId());
        requestRepository.saveAndFlush(request);
        return request;
    }

    private Plan currentPlan(UUID requestId) {
        Request request = requestRepository.findById(requestId).orElseThrow();
        return planRepository.findWithTasksById(request.getCurrentPlanId()).orElseThrow();
    }

    private SigningKey saveRegisteredKey(User owner, String publicKey) {
        return signingKeyRepository.saveAndFlush(SigningKey.builder()
                .user(owner)
                .algorithm("Ed25519")
                .publicKey(publicKey)
                .deviceName("노트북")
                .status(SigningKeyStatus.REGISTERED)
                .build());
    }

    private static Task codeTask(int order, TaskStatus status) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("로그인 실패 메시지 변경")
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts", null, null, null))
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

    // --- GET /approvals/pending ---

    @Test
    void 대기_목록은_내가_승인권자인_PENDING_작업만_보여_준다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.APPROVED),
                deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        // 후보 아님(EMP002)·다른 상태 요청은 안 보임
        saveRequest(RequestStatus.REVIEWING, deployTask(1, TaskStatus.PENDING, List.of(candidate.getId())));

        mockMvc.perform(get("/api/v1/approvals/pending").with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.items[0].requester.name").value("홍길동"))
                .andExpect(jsonPath("$.items[0].task.type").value("DEPLOY"))
                .andExpect(jsonPath("$.items[0].task.status").value("PENDING"));

        // 후보가 아니고 ANYONE 작업도 PENDING이 아니면 아무것도 없음
        mockMvc.perform(get("/api/v1/approvals/pending").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // --- GET /requests/{id}/approval-context ---

    @Test
    void 승인_컨텍스트는_해시_대상과_승인_가능_작업을_돌려준다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.PENDING),
                deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());
        String expectedHash = planHashService.hash(
                requestRepository.findById(request.getId()).orElseThrow(), plan);

        // 내부망 후보: 두 작업 모두 승인 가능, 차단 없음
        mockMvc.perform(get("/api/v1/requests/{id}/approval-context", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planHash").value(expectedHash))
                .andExpect(jsonPath("$.hashTarget.planId").value(plan.getId().toString()))
                .andExpect(jsonPath("$.hashTarget.tasks.length()").value(2))
                .andExpect(jsonPath("$.network").value("INTERNAL"))
                .andExpect(jsonPath("$.blockedReason").isEmpty())
                .andExpect(jsonPath("$.blockedTaskIds.length()").value(0))
                .andExpect(jsonPath("$.approvableTaskIds.length()").value(2));
    }

    @Test
    void 외부망에서는_배포_작업이_차단되고_사유가_붙는다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.PENDING),
                deployTask(2, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());
        UUID deployTaskId = plan.getTasks().get(1).getId();

        mockMvc.perform(get("/api/v1/requests/{id}/approval-context", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("EXTERNAL"))
                .andExpect(jsonPath("$.blockedReason").value("INTERNAL_NETWORK_ONLY"))
                .andExpect(jsonPath("$.blockedTaskIds[0]").value(deployTaskId.toString()))
                .andExpect(jsonPath("$.approvableTaskIds.length()").value(1));
    }

    // --- POST /requests/{id}/approvals ---

    @Test
    void 전량_승인되면_웹쉘에_실행을_요청하고_RUNNING이_된다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());
        String planHash = planHashService.hash(requestRepository.findById(request.getId()).orElseThrow(), plan);
        SigningKey key = saveRegisteredKey(candidate, "candidate-key");
        UUID taskId = plan.getTasks().getFirst().getId();
        UUID executionId = UUID.randomUUID();
        given(webshellClient.submitExecution(any())).willReturn(
                new WebshellClient.ExecutionResult(executionId, planHash, "RUNNING", "base64sig"));

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "%s", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), planHash, taskId, key.getId(), ENVELOPE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.approvedTaskIds[0]").value(taskId.toString()))
                .andExpect(jsonPath("$.remainingTaskIds.length()").value(0))
                .andExpect(jsonPath("$.executionRequested").value(true));

        ArgumentCaptor<WebshellClient.ExecutionRequest> payload = ArgumentCaptor.forClass(WebshellClient.ExecutionRequest.class);
        then(webshellClient).should().submitExecution(payload.capture());
        assertThat(payload.getValue().plan()).isEqualTo(planHashService.hashTarget(request, plan));
        assertThat(payload.getValue().approvals()).containsExactly(new WebshellClient.ApprovalPayload(key.getId(),
                new WebshellClient.ApprovalPayload.ApprovalEnvelope("HPKE-X25519-AES256GCM", "ZW5j", "Y2lwaGVy")));
        Request found = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(RequestStatus.RUNNING);
        Plan persistedPlan = currentPlan(request.getId());
        assertThat(persistedPlan.getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(persistedPlan.getExecutorSignature()).isEqualTo("base64sig");
        assertSavedApproval(plan, key, planHash, taskId);
        assertThat(executionRepository.findFirstByRequest_IdAndStatusOrderByCreatedAtDesc(
                request.getId(), ExecutionStatus.RUNNING)).hasValueSatisfying(execution -> {
                    assertThat(execution.getWebshellExecutionId()).isEqualTo(executionId);
                    assertThat(execution.getPlanHash()).isEqualTo(planHash);
                    assertThat(execution.getWebshellSignature()).isEqualTo("base64sig");
                });
    }

    @Test
    void 웹쉘_연결_실패면_서명은_보관되고_실행은_요청되지_않는다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());
        String planHash = planHashService.hash(requestRepository.findById(request.getId()).orElseThrow(), plan);
        SigningKey key = saveRegisteredKey(candidate, "candidate-key");
        UUID taskId = plan.getTasks().getFirst().getId();
        willThrow(new site.siyoung.beforeaibe.exception.ApiException(
                site.siyoung.beforeaibe.exception.ErrorCode.WEBSHELL_UNAVAILABLE))
                .given(webshellClient).submitExecution(any());

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "%s", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), planHash, taskId, key.getId(), ENVELOPE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.executionRequested").value(false));

        // 서명과 작업 승인은 보관됨(별도 트랜잭션이라 롤백되지 않음)
        assertSavedApproval(plan, key, planHash, taskId);
        Plan persistedPlan = currentPlan(request.getId());
        assertThat(persistedPlan.getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(persistedPlan.getExecutorSignature()).isNull();
        Request found = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(RequestStatus.PENDING_APPROVAL);
        assertThat(executionRepository.findFirstByRequest_IdAndStatusOrderByCreatedAtDesc(
                request.getId(), ExecutionStatus.RUNNING)).isEmpty();
        then(webshellClient).should().submitExecution(any());
    }

    private void assertSavedApproval(Plan plan, SigningKey key, String planHash, UUID taskId) {
        assertThat(approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId())).singleElement()
                .satisfies(approval -> {
                    assertThat(approval.getApproverId()).isEqualTo(candidate.getId());
                    assertThat(approval.getKeyId()).isEqualTo(key.getId());
                    assertThat(approval.getPlanHash()).isEqualTo(planHash);
                    assertThat(approval.getTaskIds()).containsExactly(taskId);
                    assertThat(approval.getEnvelope().getAlg()).isEqualTo("HPKE-X25519-AES256GCM");
                    assertThat(approval.getEnvelope().getEncapsulatedKey()).isEqualTo("ZW5j");
                    assertThat(approval.getEnvelope().getCiphertext()).isEqualTo("Y2lwaGVy");
                });
    }

    @Test
    void 해시가_다르면_409_PLAN_HASH_MISMATCH() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());
        SigningKey key = saveRegisteredKey(candidate, "candidate-key");
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "sha256:deadbeef", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), taskId, key.getId(), ENVELOPE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLAN_HASH_MISMATCH"));
        then(webshellClient).should(never()).submitExecution(any());
    }

    @Test
    void 승인권자가_아니면_403_NOT_APPROVER() throws Exception {
        // 후보에 EMP101만 든 배포 작업을 같은 부서 다른 직원(EMP002)이 승인하려 함
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                deployTask(1, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());
        String planHash = planHashService.hash(requestRepository.findById(request.getId()).orElseThrow(), plan);
        User other = userRepository.findByEmployeeNumber("EMP002").orElseThrow();
        SigningKey key = saveRegisteredKey(other, "other-key");
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "%s", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), planHash, taskId, key.getId(), ENVELOPE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_APPROVER"));
        assertNoApprovalChanges(request);
    }

    @Test
    void 승인_대상에_권한_없는_작업이_섞이면_어느_작업도_승인하지_않는다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.PENDING),
                deployTask(2, TaskStatus.PENDING, List.of(requester.getId())));
        Plan plan = currentPlan(request.getId());
        String planHash = planHashService.hash(request, plan);
        SigningKey key = saveRegisteredKey(candidate, "candidate-key");

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "%s", "taskIds": ["%s", "%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), planHash, plan.getTasks().get(0).getId(),
                                        plan.getTasks().get(1).getId(), key.getId(), ENVELOPE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_APPROVER"));

        assertNoApprovalChanges(request);
    }

    @Test
    void 등록되지_않은_키면_403_KEY_NOT_REGISTERED() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());
        String planHash = planHashService.hash(requestRepository.findById(request.getId()).orElseThrow(), plan);
        // PENDING 상태 키(아직 미등록)
        SigningKey pending = signingKeyRepository.saveAndFlush(SigningKey.builder()
                .user(candidate).algorithm("Ed25519").publicKey("pending-key").status(SigningKeyStatus.PENDING)
                .build());
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "%s", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), planHash, taskId, pending.getId(), ENVELOPE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("KEY_NOT_REGISTERED"));
    }

    @Test
    void 외부망에서는_승인_서명을_제출할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());
        SigningKey key = saveRegisteredKey(candidate, "candidate-key");
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "sha256:x", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), taskId, key.getId(), ENVELOPE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 승인_대기_상태가_아니면_409_INVALID_STATE() throws Exception {
        Request request = saveRequest(RequestStatus.REVIEWING, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());
        SigningKey key = saveRegisteredKey(candidate, "candidate-key");
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/approvals", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "planHash": "sha256:x", "taskIds": ["%s"],
                                 "keyId": "%s", "envelope": %s}"""
                                .formatted(plan.getId(), taskId, key.getId(), ENVELOPE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // --- POST /requests/{id}/rejections ---

    @Test
    void 작업을_거부하면_REJECTED가_된다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                deployTask(1, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());
        // fixture 저장과 다른 영속성 컨텍스트에서 읽어 JSON 후보 UUID의 DB 왕복도 확인한다.
        assertThat(plan.getTasks().getFirst().getRequiredApprovers()).containsExactly(
                new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, List.of(candidate.getId())));
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/rejections", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("""
                                {"planId": "%s", "taskIds": ["%s"], "reason": "배포 일정과 충돌합니다."}"""
                                .formatted(plan.getId(), taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.REJECTED);
        assertThat(currentPlan(request.getId()).getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.REJECTED);
    }

    @Test
    void ANYONE_작업은_후보에_명시되지_않은_사용자도_거부할_수_있다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask(1, TaskStatus.PENDING));
        Plan plan = currentPlan(request.getId());

        mockMvc.perform(post("/api/v1/requests/{id}/rejections", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("{\"planId\": \"%s\", \"taskIds\": [\"%s\"]}"
                                .formatted(plan.getId(), plan.getTasks().getFirst().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(currentPlan(request.getId()).getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.REJECTED);
        then(webshellClient).should(never()).submitExecution(any());
    }

    @Test
    void 승인권자가_아니면_거부도_403_NOT_APPROVER이고_상태를_바꾸지_않는다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                deployTask(1, TaskStatus.PENDING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());

        mockMvc.perform(post("/api/v1/requests/{id}/rejections", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002"))
                        .contentType("application/json")
                        .content("{\"planId\": \"%s\", \"taskIds\": [\"%s\"]}"
                                .formatted(plan.getId(), plan.getTasks().getFirst().getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_APPROVER"));

        assertNoApprovalChanges(request);
    }

    @Test
    void 거부_대상에_권한_없는_작업이_섞이면_어느_작업도_거부하지_않는다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL,
                codeTask(1, TaskStatus.PENDING),
                deployTask(2, TaskStatus.PENDING, List.of(requester.getId())));
        Plan plan = currentPlan(request.getId());

        mockMvc.perform(post("/api/v1/requests/{id}/rejections", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("{\"planId\": \"%s\", \"taskIds\": [\"%s\", \"%s\"]}"
                                .formatted(plan.getId(), plan.getTasks().get(0).getId(), plan.getTasks().get(1).getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_APPROVER"));

        assertNoApprovalChanges(request);
    }

    private void assertNoApprovalChanges(Request request) {
        Request persisted = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(RequestStatus.PENDING_APPROVAL);
        assertThat(persisted.getProgressVersion()).isZero();
        Plan plan = currentPlan(request.getId());
        assertThat(plan.getTasks()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING));
        assertThat(approvalRepository.findByPlan_IdOrderByCreatedAtAscIdAsc(plan.getId())).isEmpty();
        assertThat(executionRepository.findFirstByRequest_IdAndStatusOrderByCreatedAtDesc(
                request.getId(), ExecutionStatus.RUNNING)).isEmpty();
        then(webshellClient).should(never()).submitExecution(any());
    }

    @Test
    void 승인_대기_상태가_아니면_거부할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING,
                deployTask(1, TaskStatus.RUNNING, List.of(candidate.getId())));
        Plan plan = currentPlan(request.getId());
        UUID taskId = plan.getTasks().getFirst().getId();

        mockMvc.perform(post("/api/v1/requests/{id}/rejections", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content("{\"planId\": \"%s\", \"taskIds\": [\"%s\"]}".formatted(plan.getId(), taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }
}
