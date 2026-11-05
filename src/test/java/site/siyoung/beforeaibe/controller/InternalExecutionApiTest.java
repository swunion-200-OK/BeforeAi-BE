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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
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
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ExecutionEventRepository;
import site.siyoung.beforeaibe.repository.ExecutionLogRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
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

// 웹쉘 → 웹 서비스 실행 이벤트 콜백. 인증서는 테스트용 자체 서명 인증서(CN=webshell-callback)로 대신함
@SpringBootTest(properties = "webshell.callback-client-cn=webshell-callback")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InternalExecutionApiTest {

    private static final String WEBSHELL_CERT = "certs/webshell-callback.crt";
    private static final String UNKNOWN_CERT = "certs/unknown-client.crt";

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
    private ExecutionLogRepository executionLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;
    private User requester;

    @BeforeEach
    void setUp() {
        Department dept = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EMP001", false);
        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(dept)
                .repository("git@internal:auth-service.git")
                .build());
    }

    private static RequestPostProcessor webshell() throws Exception {
        return x509(WEBSHELL_CERT);
    }

    // 실행 중인 요청 + 현재 Plan + RUNNING 실행 세션
    private Execution saveExecution(RequestStatus requestStatus, PlanKind kind, ExecutionStatus executionStatus,
                                    TaskStatus... taskStatuses) {
        Request request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt("로그인 실패 메시지를 바꿔줘")
                .source(RequestSource.PROMPT)
                .status(requestStatus)
                .analysisAttempts(1)
                .build());
        Plan plan = Plan.builder()
                .request(request)
                .kind(kind)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
        for (int i = 0; i < taskStatuses.length; i++) {
            plan.addTask(task(i + 1, taskStatuses[i]));
        }
        plan = planRepository.saveAndFlush(plan);
        ReflectionTestUtils.setField(request, "currentPlanId", plan.getId());
        requestRepository.saveAndFlush(request);
        return executionRepository.saveAndFlush(Execution.builder()
                .request(request)
                .plan(plan)
                .webshellExecutionId(UUID.randomUUID())
                .planHash("sha256:4be1")
                .status(executionStatus)
                .webshellSignature("c2ln")
                .build());
    }

    private Execution saveRunningExecution(TaskStatus... taskStatuses) {
        return saveExecution(RequestStatus.RUNNING, PlanKind.NORMAL, ExecutionStatus.RUNNING, taskStatuses);
    }

    private static Task task(int order, TaskStatus status) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("작업 " + order)
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(status)
                .build();
    }

    private static UUID taskId(Execution execution, int index) {
        return execution.getPlan().getTasks().get(index).getId();
    }

    private static String event(UUID eventId, String type, Execution execution, UUID taskId, String data) {
        return """
                {"eventId": "%s", "type": "%s", "executionId": "%s", "requestId": "%s", %s
                 "at": "2026-11-04T03:10:00Z", "data": %s, "webshellSignature": "c2ln"}
                """.formatted(eventId, type, execution.getWebshellExecutionId(), execution.getRequest().getId(),
                taskId == null ? "" : "\"taskId\": \"" + taskId + "\",", data);
    }

    private org.springframework.test.web.servlet.ResultActions send(Execution execution, String body,
                                                                    RequestPostProcessor... processors) throws Exception {
        var builder = post("/api/v1/internal/executions/{executionId}/events", execution.getWebshellExecutionId())
                .contentType("application/json")
                .content(body);
        for (RequestPostProcessor processor : processors) {
            builder = builder.with(processor);
        }
        return mockMvc.perform(builder);
    }

    private Plan plan(Execution execution) {
        return planRepository.findWithTasksById(execution.getPlan().getId()).orElseThrow();
    }

    // --- 인증 ---

    @Test
    void 클라이언트_인증서가_없으면_401() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);

        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId(execution, 0), "{}"), internal())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 허용되지_않은_CN의_인증서면_401() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);

        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId(execution, 0), "{}"),
                internal(), x509(UNKNOWN_CERT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 외부망에서_온_콜백은_403_INTERNAL_NETWORK_ONLY() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);

        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId(execution, 0), "{}"),
                external(), webshell())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    // --- 작업 이벤트 ---

    @Test
    void 작업_시작과_성공_이벤트로_작업_상태가_바뀌고_이벤트가_저장된다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);
        UUID taskId = taskId(execution, 0);

        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId, "{}"), internal(), webshell())
                .andExpect(status().isNoContent());
        assertThat(plan(execution).getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.RUNNING);

        send(execution, event(UUID.randomUUID(), "TASK_SUCCEEDED", execution, taskId,
                "{\"status\": \"SUCCEEDED\", \"message\": \"diff 적용 완료\"}"), internal(), webshell())
                .andExpect(status().isNoContent());
        assertThat(plan(execution).getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executionEventRepository.findAll()).hasSize(2);
    }

    @Test
    void 같은_eventId를_다시_보내도_한_번만_반영된다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);
        String body = event(UUID.randomUUID(), "TASK_STARTED", execution, taskId(execution, 0), "{}");

        send(execution, body, internal(), webshell()).andExpect(status().isNoContent());
        // 작업이 이미 RUNNING이라 새 이벤트라면 409지만, 같은 eventId라 그대로 204
        send(execution, body, internal(), webshell()).andExpect(status().isNoContent());

        assertThat(executionEventRepository.findAll()).hasSize(1);
    }

    @Test
    void 이미_끝난_작업에_시작_이벤트가_오면_409() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.SUCCEEDED);

        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId(execution, 0), "{}"),
                internal(), webshell())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 작업_이벤트에_taskId가_없으면_400() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);

        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, null, "{}"), internal(), webshell())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // --- 로그 ---

    @Test
    void 로그_이벤트는_실행_로그로_저장된다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);
        UUID taskId = taskId(execution, 0);

        send(execution, event(UUID.randomUUID(), "LOG", execution, taskId,
                "{\"sequence\": 1, \"message\": \"diff 적용 시작\"}"), internal(), webshell())
                .andExpect(status().isNoContent());

        List<ExecutionLog> logs = executionLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getSequence()).isEqualTo(1);
        assertThat(logs.getFirst().getMessage()).isEqualTo("diff 적용 시작");
        assertThat(logs.getFirst().getTask().getId()).isEqualTo(taskId);
        assertThat(logs.getFirst().getWebshellSignature()).isEqualTo("c2ln");
    }

    @Test
    void 로그_이벤트에_sequence가_없으면_400() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);

        send(execution, event(UUID.randomUUID(), "LOG", execution, null, "{\"message\": \"로그\"}"),
                internal(), webshell())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // --- 최종 상태 ---

    @Test
    void 최종_상태_이벤트로_실행과_요청이_끝나고_시작하지_않은_작업은_SKIPPED가_된다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.FAILED, TaskStatus.APPROVED);

        send(execution, event(UUID.randomUUID(), "EXECUTION_FINISHED", execution, null,
                "{\"status\": \"PARTIALLY_COMPLETED\"}"), internal(), webshell())
                .andExpect(status().isNoContent());

        Execution found = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.PARTIALLY_COMPLETED);
        assertThat(requestRepository.findById(found.getRequest().getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.PARTIALLY_COMPLETED);
        assertThat(plan(execution).getTasks()).extracting(Task::getStatus)
                .containsExactly(TaskStatus.FAILED, TaskStatus.SKIPPED);
    }

    @Test
    void 종료_이벤트를_받지_않은_작업이_남아_있으면_최종_상태_이벤트는_409() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.SUCCEEDED, TaskStatus.RUNNING);

        send(execution, event(UUID.randomUUID(), "EXECUTION_FINISHED", execution, null,
                "{\"status\": \"COMPLETED\"}"), internal(), webshell())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.RUNNING);
    }

    // --- 진행 트리 STEP ---

    @Test
    void 작업_이벤트와_로그로_진행_트리에_STEP과_logRange가_생긴다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);
        UUID taskId = taskId(execution, 0);
        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId, "{\"label\": \"diff 적용\"}"),
                internal(), webshell()).andExpect(status().isNoContent());
        send(execution, event(UUID.randomUUID(), "LOG", execution, taskId, "{\"sequence\": 3, \"message\": \"시작\"}"),
                internal(), webshell()).andExpect(status().isNoContent());
        send(execution, event(UUID.randomUUID(), "LOG", execution, taskId, "{\"sequence\": 7, \"message\": \"끝\"}"),
                internal(), webshell()).andExpect(status().isNoContent());
        send(execution, event(UUID.randomUUID(), "TASK_SUCCEEDED", execution, taskId, "{}"),
                internal(), webshell()).andExpect(status().isNoContent());

        String step = "$.root.children[0].children[0].children[0]";
        mockMvc.perform(get("/api/v1/requests/{id}/progress", execution.getRequest().getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(step + ".id").value("step:" + execution.getId() + ":" + taskId))
                .andExpect(jsonPath(step + ".type").value("STEP"))
                .andExpect(jsonPath(step + ".label").value("diff 적용"))
                .andExpect(jsonPath(step + ".status").value("SUCCEEDED"))
                .andExpect(jsonPath(step + ".logRange.from").value(3))
                .andExpect(jsonPath(step + ".logRange.to").value(7));
    }

    @Test
    void 실행_중인_STEP은_logRange_to가_null이고_label이_없으면_작업_제목을_쓴다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.APPROVED);
        UUID taskId = taskId(execution, 0);
        send(execution, event(UUID.randomUUID(), "TASK_STARTED", execution, taskId, "{}"),
                internal(), webshell()).andExpect(status().isNoContent());
        send(execution, event(UUID.randomUUID(), "LOG", execution, taskId, "{\"sequence\": 1, \"message\": \"시작\"}"),
                internal(), webshell()).andExpect(status().isNoContent());

        String step = "$.root.children[0].children[0].children[0]";
        mockMvc.perform(get("/api/v1/requests/{id}/progress", execution.getRequest().getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(step + ".label").value("작업 1"))
                .andExpect(jsonPath(step + ".status").value("RUNNING"))
                .andExpect(jsonPath(step + ".logRange.from").value(1))
                .andExpect(jsonPath(step + ".logRange.to").value(org.hamcrest.Matchers.nullValue()));
    }

    private String requesterToken() throws Exception {
        return bearer(json(loginInternal(mockMvc, "EMP001"), "$.accessToken"));
    }

    @Test
    void 롤백_Plan이_실패로_끝나면_요청은_ROLLBACK_FAILED가_된다() throws Exception {
        Execution execution = saveExecution(RequestStatus.ROLLING_BACK, PlanKind.ROLLBACK, ExecutionStatus.RUNNING,
                TaskStatus.FAILED);

        send(execution, event(UUID.randomUUID(), "EXECUTION_FINISHED", execution, null, "{\"status\": \"FAILED\"}"),
                internal(), webshell())
                .andExpect(status().isNoContent());

        assertThat(requestRepository.findById(execution.getRequest().getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.ROLLBACK_FAILED);
    }

    @Test
    void 롤백_Plan이_완료되면_요청은_ROLLED_BACK이_된다() throws Exception {
        Execution execution = saveExecution(RequestStatus.ROLLING_BACK, PlanKind.ROLLBACK, ExecutionStatus.RUNNING,
                TaskStatus.SUCCEEDED);

        send(execution, event(UUID.randomUUID(), "EXECUTION_FINISHED", execution, null,
                "{\"status\": \"COMPLETED\"}"), internal(), webshell())
                .andExpect(status().isNoContent());

        assertThat(requestRepository.findById(execution.getRequest().getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.ROLLED_BACK);
    }

    @Test
    void 최종_상태_값이_허용되지_않으면_400() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);

        send(execution, event(UUID.randomUUID(), "EXECUTION_FINISHED", execution, null, "{\"status\": \"KILLED\"}"),
                internal(), webshell())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 이미_끝난_실행_세션에_이벤트가_오면_409() throws Exception {
        Execution execution = saveExecution(RequestStatus.KILLED, PlanKind.NORMAL, ExecutionStatus.KILLED,
                TaskStatus.SKIPPED);

        send(execution, event(UUID.randomUUID(), "EXECUTION_FINISHED", execution, null,
                "{\"status\": \"COMPLETED\"}"), internal(), webshell())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    // --- 경보·형식 오류 ---

    @Test
    void 경보_이벤트는_상태를_바꾸지_않고_저장만_한다() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);

        send(execution, event(UUID.randomUUID(), "POLICY_MISMATCH", execution, null,
                "{\"expectedVersion\": 8, \"actualVersion\": 7}"), internal(), webshell())
                .andExpect(status().isNoContent());

        assertThat(executionEventRepository.findAll()).singleElement()
                .satisfies(saved -> assertThat(saved.getType()).isEqualTo("POLICY_MISMATCH"));
        assertThat(requestRepository.findById(execution.getRequest().getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.RUNNING);
    }

    @Test
    void 지원하지_않는_이벤트_종류면_400() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);

        send(execution, event(UUID.randomUUID(), "UNKNOWN", execution, null, "{}"), internal(), webshell())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 없는_실행_세션이면_404() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);
        UUID unknown = UUID.randomUUID();
        String body = event(UUID.randomUUID(), "LOG", execution, null, "{\"sequence\": 1, \"message\": \"로그\"}")
                .replace(execution.getWebshellExecutionId().toString(), unknown.toString());

        mockMvc.perform(post("/api/v1/internal/executions/{executionId}/events", unknown)
                        .with(internal()).with(webshell())
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 경로와_본문의_executionId가_다르면_400() throws Exception {
        Execution execution = saveRunningExecution(TaskStatus.RUNNING);
        String body = event(UUID.randomUUID(), "LOG", execution, null, "{\"sequence\": 1, \"message\": \"로그\"}");

        mockMvc.perform(post("/api/v1/internal/executions/{executionId}/events", UUID.randomUUID())
                        .with(internal()).with(webshell())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
