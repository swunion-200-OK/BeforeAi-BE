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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Document;
import site.siyoung.beforeaibe.entity.DocumentVersion;
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
import site.siyoung.beforeaibe.entity.value.TaskExplanation;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.DocumentRepository;
import site.siyoung.beforeaibe.repository.DocumentVersionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.PlanHashService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
@Transactional
class RequestRollbackApiTest {

    private static final String PATH = "docs/login.md";

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
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private PlanHashService planHashService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;
    private User requester;
    private User candidate;

    // 같은 사번으로 여러 번 로그인하지 않도록 토큰을 기억해 둠
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        candidate = saveUser(userRepository, passwordEncoder, "EMP101", Role.EMPLOYEE, dev.getId());

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
                .prompt("로그인 안내 문서를 고쳐줘")
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
        planRepository.saveAndFlush(plan);
        ReflectionTestUtils.setField(request, "currentPlanId", plan.getId());
        return requestRepository.saveAndFlush(request);
    }

    // 문서 v1(이전 내용), v2(이 요청이 적용한 내용)를 저장함
    private void saveAppliedDocument(Request request) {
        Document document = documentRepository.saveAndFlush(Document.builder()
                .project(project).title("운영 안내").path(PATH).currentVersion(2).build());
        documentVersionRepository.saveAndFlush(DocumentVersion.builder()
                .document(document).version(1).body("이전 안내").request(request).build());
        documentVersionRepository.saveAndFlush(DocumentVersion.builder()
                .document(document).version(2).body("새 안내").request(request).build());
    }

    private Task docTask(List<UUID> candidates) {
        return docTask(1, candidates);
    }

    private Task docTask(int order, List<UUID> candidates) {
        return Task.builder()
                .order(order)
                .type(TaskType.DOC)
                .risk(Risk.LOW)
                .title("로그인 안내 문서 수정")
                .explanation(new TaskExplanation("안내 문서를 바꾼다", List.of(), List.of(), "이전 버전으로 복원"))
                .target(PATH)
                .content(new TaskContent(null, null, null, "새 안내"))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.PROJECT_OWNER, 1, candidates)))
                .status(TaskStatus.SUCCEEDED)
                .build();
    }

    private static Task codeTask() {
        return Task.builder()
                .order(1)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("로그인 실패 메시지 변경")
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.SUCCEEDED)
                .build();
    }

    private UUID firstTaskId(Request request) {
        return planRepository.findWithTasksById(request.getCurrentPlanId()).orElseThrow()
                .getTasks().getFirst().getId();
    }

    private static String body(UUID taskId, int targetVersion) {
        return """
                {"taskIds": ["%s"], "targetVersion": %d, "reason": "안내 문서를 이전 내용으로 복원"}
                """.formatted(taskId, targetVersion);
    }

    @Test
    void 완료된_요청의_문서_작업으로_롤백_Plan을_만들고_해시를_발급한다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(List.of(candidate.getId())));
        saveAppliedDocument(request);
        UUID taskId = firstTaskId(request);
        UUID previousPlanId = request.getCurrentPlanId();

        MvcResult result = mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content(body(taskId, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.source").value("PROMPT"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.plan.kind").value("ROLLBACK"))
                .andReturn();

        UUID planId = UUID.fromString(json(result, "$.plan.id"));
        Plan plan = planRepository.findWithTasksById(planId).orElseThrow();
        Request saved = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(planId).isNotEqualTo(previousPlanId);
        assertThat(saved.getCurrentPlanId()).isEqualTo(planId);
        assertThat(saved.getStatus()).isEqualTo(RequestStatus.PENDING_APPROVAL);
        assertThat(plan.getKind()).isEqualTo(PlanKind.ROLLBACK);
        assertThat(plan.getBaseCommit()).isEqualTo("9b2f4e1");
        assertThat(plan.getTasks()).singleElement().satisfies(task -> {
            assertThat(task.getType()).isEqualTo(TaskType.DOC);
            assertThat(task.getTarget()).isEqualTo(PATH);
            assertThat(task.getContent().body()).isEqualTo("이전 안내");
            // 본문이 원본 설명과 달라졌으므로 설명은 복사하지 않음
            assertThat(task.getExplanation()).isNull();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(task.getRequiredApprovers()).singleElement()
                    .satisfies(approver -> assertThat(approver.candidates()).containsExactly(candidate.getId()));
        });
        assertThat(json(result, "$.planHash")).startsWith("sha256:")
                .isEqualTo(planHashService.hash(saved, plan));
    }

    @Test
    void 문서_작업의_승인_후보도_롤백할_수_있다() throws Exception {
        Request request = saveRequest(RequestStatus.KILLED, docTask(List.of(candidate.getId())));
        saveAppliedDocument(request);

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP101"))
                        .contentType("application/json")
                        .content(body(firstTaskId(request), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.kind").value("ROLLBACK"));
    }

    @Test
    void 요청자도_승인_후보도_아니면_롤백할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(List.of(candidate.getId())));
        saveAppliedDocument(request);

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002"))
                        .contentType("application/json")
                        .content(body(firstTaskId(request), 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 롤백할_수_없는_상태면_409다() throws Exception {
        Request request = saveRequest(RequestStatus.RUNNING, docTask(List.of()));
        saveAppliedDocument(request);

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content(body(firstTaskId(request), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 문서가_아닌_작업은_롤백할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, codeTask());

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content(body(firstTaskId(request), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("문서가 아닌 작업은 롤백할 수 없습니다"));
    }

    @Test
    void 같은_문서를_가리키는_작업을_함께_롤백할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(1, List.of()), docTask(2, List.of()));
        saveAppliedDocument(request);
        List<Task> tasks = planRepository.findWithTasksById(request.getCurrentPlanId()).orElseThrow().getTasks();

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"taskIds\": [\"%s\", \"%s\"], \"targetVersion\": 1}"
                                .formatted(tasks.get(0).getId(), tasks.get(1).getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void 대상_버전이_없으면_400이다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(List.of()));
        saveAppliedDocument(request);

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content(body(firstTaskId(request), 9)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 요청에_없는_작업이면_404다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(List.of()));
        saveAppliedDocument(request);

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content(body(UUID.randomUUID(), 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void taskIds가_비어_있으면_400이다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(List.of()));

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"taskIds\": [], \"targetVersion\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 외부망에서는_롤백할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask(List.of()));
        saveAppliedDocument(request);

        mockMvc.perform(post("/api/v1/requests/{id}/rollback", request.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content(body(firstTaskId(request), 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }
}
