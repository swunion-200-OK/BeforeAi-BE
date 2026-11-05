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
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.DocumentRepository;
import site.siyoung.beforeaibe.repository.DocumentVersionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class DocumentApiTest {

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
    private PasswordEncoder passwordEncoder;

    private Project project;
    private User requester;

    // 같은 사번으로 여러 번 로그인하지 않도록 토큰을 기억해 둠
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());

        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build());
    }

    private String token(String employeeNumber) throws Exception {
        String token = tokens.get(employeeNumber);
        if (token == null) {
            MvcResult result = loginInternal(mockMvc, employeeNumber);
            token = json(result, "$.accessToken");
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

    private Document saveDocument(String path, int currentVersion) {
        return documentRepository.saveAndFlush(Document.builder()
                .project(project).title("운영 안내").path(path).currentVersion(currentVersion).build());
    }

    private void saveVersion(Document document, int version, String body, Request request) {
        documentVersionRepository.saveAndFlush(DocumentVersion.builder()
                .document(document).version(version).body(body).request(request).build());
    }

    private static Task docTask(String body) {
        return Task.builder()
                .order(1)
                .type(TaskType.DOC)
                .risk(Risk.LOW)
                .title("로그인 안내 문서 수정")
                .target(PATH)
                .content(new TaskContent(null, null, null, body))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(TaskStatus.PENDING)
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
                .status(TaskStatus.PENDING)
                .build();
    }

    private UUID firstTaskId(Request request) {
        return planRepository.findWithTasksById(request.getCurrentPlanId()).orElseThrow()
                .getTasks().getFirst().getId();
    }

    @Test
    void 프로젝트_문서_목록을_커서_페이지로_조회한다() throws Exception {
        saveDocument("docs/a.md", 1);
        saveDocument("docs/b.md", 2);
        saveDocument("docs/c.md", 3);

        MvcResult first = mockMvc.perform(get("/api/v1/projects/{id}/documents", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();

        mockMvc.perform(get("/api/v1/projects/{id}/documents", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .param("limit", "2")
                        .param("cursor", json(first, "$.nextCursor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void 외부망에서도_문서_목록을_조회할_수_있다() throws Exception {
        saveDocument(PATH, 1);

        mockMvc.perform(get("/api/v1/projects/{id}/documents", project.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].path").value(PATH))
                .andExpect(jsonPath("$.items[0].currentVersion").value(1));
    }

    @Test
    void 없는_프로젝트의_문서_목록은_404다() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/documents", UUID.randomUUID()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void limit이_범위를_벗어나면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/documents", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 문서_버전_이력을_최신순으로_조회한다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask("v3"));
        Document document = saveDocument(PATH, 3);
        saveVersion(document, 1, "v1", request);
        saveVersion(document, 2, "v2", request);
        saveVersion(document, 3, "v3", request);

        MvcResult first = mockMvc.perform(get("/api/v1/documents/{id}/versions", document.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[1].version").value(2))
                .andExpect(jsonPath("$.items[0].requestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andReturn();

        mockMvc.perform(get("/api/v1/documents/{id}/versions", document.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .param("limit", "2")
                        .param("cursor", json(first, "$.nextCursor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void 없는_문서의_버전_이력은_404다() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/versions", UUID.randomUUID()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 특정_문서_버전_내용을_조회한다() throws Exception {
        Request request = saveRequest(RequestStatus.COMPLETED, docTask("v2"));
        Document document = saveDocument(PATH, 2);
        saveVersion(document, 1, "# 운영 안내\n이전 버전", request);
        saveVersion(document, 2, "# 운영 안내\n현재 버전", request);

        mockMvc.perform(get("/api/v1/documents/{id}/versions/{version}", document.getId(), 1).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.body").value("# 운영 안내\n이전 버전"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void 없는_문서_버전은_404다() throws Exception {
        Document document = saveDocument(PATH, 1);

        mockMvc.perform(get("/api/v1/documents/{id}/versions/{version}", document.getId(), 9).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 적용_전_문서_작업은_현재_버전과의_diff를_보여준다() throws Exception {
        Request other = saveRequest(RequestStatus.COMPLETED, docTask("기존 안내"));
        Document document = saveDocument(PATH, 1);
        saveVersion(document, 1, "기존 안내\n", other);
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, docTask("새 안내\n"));
        UUID taskId = firstTaskId(request);

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), taskId)
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.body").value("새 안내\n"))
                .andExpect(jsonPath("$.diff").value(containsString("--- a/" + PATH)))
                .andExpect(jsonPath("$.diff").value(containsString("+++ b/" + PATH)))
                .andExpect(jsonPath("$.diff").value(containsString("-기존 안내")))
                .andExpect(jsonPath("$.diff").value(containsString("+새 안내")));
    }

    @Test
    void 이미_적용된_문서_작업은_적용_직전_버전과의_diff를_보여준다() throws Exception {
        Request other = saveRequest(RequestStatus.COMPLETED, docTask("v1"));
        Request request = saveRequest(RequestStatus.COMPLETED, appliedDocTask("적용된 안내"));
        Document document = saveDocument(PATH, 2);
        saveVersion(document, 1, "이전 안내", other);
        saveVersion(document, 2, "적용된 안내", request);

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(request))
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff").value(containsString("-이전 안내")))
                .andExpect(jsonPath("$.diff").value(containsString("+적용된 안내")));
    }

    private static Task appliedDocTask(String body) {
        Task task = docTask(body);
        task.changeStatus(TaskStatus.SUCCEEDED);
        return task;
    }

    @Test
    void 승인_전_롤백_작업은_같은_요청이_만든_버전이_있어도_현재_버전과의_diff를_보여준다() throws Exception {
        Request other = saveRequest(RequestStatus.COMPLETED, docTask("첫 버전"));
        // 롤백 Plan은 원래 요청에 속하므로 이 요청이 이미 버전을 만든 상태에서 새 작업이 생김
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, docTask("첫 버전"));
        Document document = saveDocument(PATH, 2);
        saveVersion(document, 1, "첫 버전", other);
        saveVersion(document, 2, "둘째 버전", request);

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(request))
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff").value(containsString("-둘째 버전")))
                .andExpect(jsonPath("$.diff").value(containsString("+첫 버전")));
    }

    @Test
    void 적용된_롤백_작업은_롤백_직전_버전과의_diff를_보여준다() throws Exception {
        Request other = saveRequest(RequestStatus.COMPLETED, docTask("첫 버전"));
        Request request = saveRequest(RequestStatus.ROLLED_BACK, appliedDocTask("첫 버전"));
        Document document = saveDocument(PATH, 3);
        saveVersion(document, 1, "첫 버전", other);
        saveVersion(document, 2, "둘째 버전", request);
        saveVersion(document, 3, "첫 버전", request);

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(request))
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff").value(containsString("-둘째 버전")))
                .andExpect(jsonPath("$.diff").value(containsString("+첫 버전")));
    }

    @Test
    void 새로_만드는_문서는_documentId_없이_빈_문서와의_diff를_보여준다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, docTask("새 문서"));

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(request))
                        .with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(nullValue()))
                .andExpect(jsonPath("$.diff").value(containsString("+새 문서")));
    }

    @Test
    void 문서가_아닌_작업은_문서를_조회할_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, codeTask());

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(request))
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 다른_요청의_작업은_404다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, docTask("안내"));
        Request other = saveRequest(RequestStatus.PENDING_APPROVAL, docTask("다른 안내"));

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(other))
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 요청_조회_권한이_없으면_작업_문서를_볼_수_없다() throws Exception {
        Request request = saveRequest(RequestStatus.PENDING_APPROVAL, docTask("안내"));

        mockMvc.perform(get("/api/v1/requests/{id}/tasks/{taskId}/document", request.getId(), firstTaskId(request))
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }
}
