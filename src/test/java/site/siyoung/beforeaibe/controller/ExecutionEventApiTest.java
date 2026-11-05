package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
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
import site.siyoung.beforeaibe.repository.ExecutionEventRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.PlanHashService;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.rawPublicKey;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.sign;

// 웹쉘 콜백 (POST /internal/executions/{executionId}/events). 테스트용 웹쉘 서명 키로 실제 Ed25519 서명을 만들어 보냄
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ExecutionEventApiTest {

    private static final KeyPair WEBSHELL_KEY;

    static {
        try {
            WEBSHELL_KEY = ApiTestSupport.ed25519KeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void webshellSigningKey(DynamicPropertyRegistry registry) {
        registry.add("webshell.signing-public-key", () -> rawPublicKey(WEBSHELL_KEY));
    }

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
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionEventRepository executionEventRepository;

    @Autowired
    private PlanHashService planHashService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User requester;
    private Project project;
    private Request request;
    private Plan plan;
    private Task codeTask;
    private Task deployTask;
    private Execution execution;

    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스").department(dev).repository("git@internal:auth-service.git").build());
        saveRunning(PlanKind.NORMAL, RequestStatus.RUNNING);
    }

    private void saveRunning(PlanKind kind, RequestStatus status) {
        request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt("로그인 실패 시 에러 메시지를 바꾸고 스테이징에 배포해줘")
                .source(RequestSource.PROMPT)
                .status(status)
                .analysisAttempts(1)
                .build());
        Plan newPlan = Plan.builder().request(request).kind(kind).repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1").planHash("sha256:4be1").build();
        newPlan.addTask(task(1, "로그인 실패 메시지 변경", TaskType.CODE_FEATURE, "src/auth/login.ts"));
        newPlan.addTask(task(2, "스테이징 배포", TaskType.DEPLOY, "staging"));
        plan = planRepository.saveAndFlush(newPlan);
        ReflectionTestUtils.setField(request, "currentPlanId", plan.getId());
        requestRepository.saveAndFlush(request);
        codeTask = plan.getTasks().get(0);
        deployTask = plan.getTasks().get(1);
        execution = executionRepository.saveAndFlush(Execution.builder()
                .request(request)
                .plan(plan)
                .webshellExecutionId(UUID.randomUUID())
                .planHash("sha256:4be1")
                .status(ExecutionStatus.RUNNING)
                .webshellSignature("c2ln")
                .build());
    }

    private Task task(int order, String title, TaskType type, String target) {
        return Task.builder()
                .order(order)
                .type(type)
                .risk(Risk.MEDIUM)
                .title(title)
                .target(target)
                .content(new TaskContent("--- a\n+++ b", null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.APPROVED)
                .build();
    }

    // 웹쉘이 만드는 것과 같은 이벤트: 본문에서 webshellSignature를 뺀 객체를 JCS로 정규화해 Ed25519로 서명
    private Map<String, Object> event(UUID eventId, String type, Task task, Map<String, Object> data) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId.toString());
        body.put("type", type);
        body.put("executionId", execution.getWebshellExecutionId().toString());
        body.put("requestId", request.getId().toString());
        if (task != null) {
            body.put("taskId", task.getId().toString());
        }
        body.put("at", "2026-11-04T03:10:00Z");
        if (data != null) {
            body.put("data", data);
        }
        body.put("webshellSignature", sign(WEBSHELL_KEY, planHashService.canonicalize(body)));
        return body;
    }

    private Map<String, Object> event(String type, Task task, Map<String, Object> data) throws Exception {
        return event(UUID.randomUUID(), type, task, data);
    }

    private ResultActions send(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/internal/executions/{id}/events", execution.getWebshellExecutionId())
                .with(internal())
                .contentType("application/json")
                .content(toJson(body)));
    }

    // 정규화 문자열은 키 순서만 다른 같은 JSON이므로 요청 본문으로도 그대로 쓸 수 있음
    private String toJson(Map<String, Object> body) {
        return planHashService.canonicalize(body);
    }

    private String token() throws Exception {
        return bearer(json(loginInternal(mockMvc, "EMP001"), "$.accessToken"));
    }

    @Test
    void 작업_시작과_성공_이벤트로_작업_상태와_STEP_노드가_바뀐다() throws Exception {
        send(event("TASK_STARTED", codeTask, Map.of("message", "diff 적용"))).andExpect(status().isNoContent());
        assertThat(codeTask.getStatus()).isEqualTo(TaskStatus.RUNNING);

        String token = token();
        mockMvc.perform(get("/api/v1/requests/{id}/progress", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.children[0].children[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].type").value("STEP"))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].id").value("step:" + codeTask.getId()))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].label").value("diff 적용"))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].status").value("RUNNING"));

        send(event("TASK_SUCCEEDED", codeTask, Map.of("status", "SUCCEEDED", "message", "diff 적용 완료")))
                .andExpect(status().isNoContent());
        assertThat(codeTask.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        mockMvc.perform(get("/api/v1/requests/{id}/progress", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.root.children[0].children[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].status").value("SUCCEEDED"));
    }

    @Test
    void 로그_이벤트는_서명과_함께_실행_로그로_저장되고_STEP의_logRange가_된다() throws Exception {
        send(event("TASK_STARTED", codeTask, null)).andExpect(status().isNoContent());
        send(event("LOG", codeTask, Map.of("sequence", 1, "message", "patching src/auth/login.ts")))
                .andExpect(status().isNoContent());
        send(event("LOG", codeTask, Map.of("sequence", 2, "message", "done"))).andExpect(status().isNoContent());

        String token = token();
        mockMvc.perform(get("/api/v1/requests/{id}/progress", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].label").value("src/auth/login.ts"))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].logRange.from").value(1))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].logRange.to").value(org.hamcrest.Matchers.nullValue()));

        send(event("TASK_SUCCEEDED", codeTask, null)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/requests/{id}/progress", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.root.children[0].children[0].children[0].logRange.to").value(2));
        mockMvc.perform(get("/api/v1/requests/{id}/logs", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].message").value("patching src/auth/login.ts"))
                .andExpect(jsonPath("$.items[0].taskId").value(codeTask.getId().toString()));
    }

    @Test
    void 실행_종료_이벤트로_실행과_요청의_최종_상태가_정해지고_끝나지_않은_작업은_건너뛴다() throws Exception {
        send(event("TASK_SUCCEEDED", codeTask, null)).andExpect(status().isNoContent());
        send(event("EXECUTION_FINISHED", null, Map.of("status", "PARTIALLY_COMPLETED")))
                .andExpect(status().isNoContent());

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PARTIALLY_COMPLETED);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PARTIALLY_COMPLETED);
        assertThat(codeTask.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(deployTask.getStatus()).isEqualTo(TaskStatus.SKIPPED);
    }

    @Test
    void 롤백_Plan_실행이_실패로_끝나면_요청은_롤백_실패가_된다() throws Exception {
        saveRunning(PlanKind.ROLLBACK, RequestStatus.ROLLING_BACK);

        send(event("EXECUTION_FINISHED", null, Map.of("status", "FAILED"))).andExpect(status().isNoContent());

        assertThat(request.getStatus()).isEqualTo(RequestStatus.ROLLBACK_FAILED);
    }

    @Test
    void 롤백_Plan_실행이_완료되면_요청은_롤백됨이_된다() throws Exception {
        saveRunning(PlanKind.ROLLBACK, RequestStatus.ROLLING_BACK);

        send(event("EXECUTION_FINISHED", null, Map.of("status", "COMPLETED"))).andExpect(status().isNoContent());

        assertThat(request.getStatus()).isEqualTo(RequestStatus.ROLLED_BACK);
    }

    @Test
    void 같은_eventId가_다시_오면_한_번만_반영한다() throws Exception {
        Map<String, Object> body = event("LOG", null, Map.of("sequence", 1, "message", "start"));

        send(body).andExpect(status().isNoContent());
        send(body).andExpect(status().isNoContent());

        assertThat(executionEventRepository.count()).isEqualTo(1);
    }

    @Test
    void 서명이_맞지_않으면_반영하지_않는다() throws Exception {
        Map<String, Object> body = event("TASK_SUCCEEDED", codeTask, null);
        // 서명한 뒤 결과를 바꿔치기
        body.put("type", "TASK_FAILED");

        send(body).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertThat(codeTask.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(executionEventRepository.count()).isZero();
    }

    @Test
    void 정해진_정규화_문자열에_서명한_이벤트를_받는다() throws Exception {
        // 웹쉘 팀과 맞출 서명 대상 예시: 키를 UTF-16 순으로 정렬하고 공백 없이 직렬화
        UUID eventId = UUID.fromString("5f0c7d0e-0000-4000-8000-000000000001");
        String canonical = ("{\"at\":\"2026-11-04T03:10:00Z\",\"data\":{\"message\":\"diff 적용 완료\","
                + "\"status\":\"SUCCEEDED\"},\"eventId\":\"%s\",\"executionId\":\"%s\",\"requestId\":\"%s\","
                + "\"taskId\":\"%s\",\"type\":\"TASK_SUCCEEDED\"}").formatted(eventId,
                execution.getWebshellExecutionId(), request.getId(), codeTask.getId());
        String body = canonical.substring(0, canonical.length() - 1)
                + ",\"webshellSignature\":\"" + sign(WEBSHELL_KEY, canonical) + "\"}";

        mockMvc.perform(post("/api/v1/internal/executions/{id}/events", execution.getWebshellExecutionId())
                        .with(internal()).contentType("application/json").content(body))
                .andExpect(status().isNoContent());
        assertThat(codeTask.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void 외부망에서는_콜백을_받지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/internal/executions/{id}/events", execution.getWebshellExecutionId())
                        .with(external())
                        .contentType("application/json")
                        .content(toJson(event("TASK_STARTED", codeTask, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 실행_중이_아닌_요청에는_작업_결과를_반영하지_않는다() throws Exception {
        request.kill();
        requestRepository.saveAndFlush(request);

        send(event("TASK_SUCCEEDED", codeTask, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 경로와_본문의_executionId가_다르면_거부한다() throws Exception {
        mockMvc.perform(post("/api/v1/internal/executions/{id}/events", UUID.randomUUID())
                        .with(internal())
                        .contentType("application/json")
                        .content(toJson(event("TASK_STARTED", codeTask, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 알_수_없는_이벤트_종류와_형식이_틀린_로그는_거부한다() throws Exception {
        send(event("TASK_PAUSED", codeTask, null)).andExpect(status().isBadRequest());
        send(event("LOG", null, Map.of("message", "sequence 없음"))).andExpect(status().isBadRequest());
        send(event("EXECUTION_FINISHED", null, Map.of("status", "KILLED"))).andExpect(status().isBadRequest());
    }
}
