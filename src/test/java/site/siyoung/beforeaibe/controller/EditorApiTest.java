package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

// 3.8 웹 코드 에디터 API 테스트 (CORE-05)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class EditorApiTest {

    private static final String REPOSITORY = "git@internal:auth-service.git";
    private static final String BASE_COMMIT = "9b2f4e1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private site.siyoung.beforeaibe.repository.DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private WebshellClient webshellClient;

    private Project project;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        Department department = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, department.getId());
        project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(department)
                .repository(REPOSITORY)
                .build());
        token = bearer(json(loginInternal(mockMvc, "EMP001"), "$.accessToken"));
    }

    // ----- #1 GET /projects/{projectId}/files -----

    @Test
    void 파일_목록은_기준_커밋과_항목을_돌려준다() throws Exception {
        given(webshellClient.listFiles(eq(REPOSITORY), any(), any()))
                .willReturn(new WebshellClient.FileListResult(BASE_COMMIT, "src/auth",
                        List.of(new WebshellClient.FileItem("session.ts", "src/auth/session.ts", "FILE"))));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", project.getId())
                        .with(internal())
                        .param("path", "src/auth")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCommit").value(BASE_COMMIT))
                .andExpect(jsonPath("$.path").value("src/auth"))
                .andExpect(jsonPath("$.items[0].name").value("session.ts"))
                .andExpect(jsonPath("$.items[0].type").value("FILE"));
    }

    @Test
    void 파일_목록_외부망_요청은_403() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/files", project.getId())
                        .with(external())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
        then(webshellClient).should(never()).listFiles(any(), any(), any());
    }

    // ----- #2 GET /projects/{projectId}/files/content -----

    @Test
    void 파일_내용을_돌려준다() throws Exception {
        given(webshellClient.readFile(eq(REPOSITORY), eq("src/auth/session.ts"), any()))
                .willReturn(new WebshellClient.FileContentResult("src/auth/session.ts", "main", BASE_COMMIT,
                        "export const loginTimeoutMs = 30000;\n"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files/content", project.getId())
                        .with(internal())
                        .param("path", "src/auth/session.ts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/auth/session.ts"))
                .andExpect(jsonPath("$.baseCommit").value(BASE_COMMIT))
                .andExpect(jsonPath("$.content").value("export const loginTimeoutMs = 30000;\n"));
    }

    @Test
    void 파일_내용_path가_없으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/files/content", project.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
    }

    // ----- #3 POST /projects/{projectId}/editor/changes -----

    @Test
    void 에디터_변경은_EDITOR_요청을_만든다() throws Exception {
        given(webshellClient.currentCommit(eq(REPOSITORY), any())).willReturn(BASE_COMMIT);

        String body = """
                {
                  "baseCommit": "%s",
                  "description": "로그인 타임아웃 긴급 수정",
                  "files": [
                    { "path": "src/auth/session.ts", "patch": "--- a/x\\n+++ b/x\\n@@ -1 +1 @@\\n-a\\n+b\\n" }
                  ]
                }
                """.formatted(BASE_COMMIT);

        String requestId = json(mockMvc.perform(post("/api/v1/projects/{projectId}/editor/changes", project.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.source").value("EDITOR"))
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn(), "$.requestId");

        Request saved = requestRepository.findById(UUID.fromString(requestId)).orElseThrow();
        assertThat(saved.getSource()).isEqualTo(RequestSource.EDITOR);
        assertThat(saved.getStatus()).isEqualTo(RequestStatus.ANALYZING);
        assertThat(saved.getPrompt()).isEqualTo("로그인 타임아웃 긴급 수정");
        assertThat(saved.getCurrentPlanId()).isNotNull();

        Plan plan = planRepository.findWithTasksById(saved.getCurrentPlanId()).orElseThrow();
        assertThat(plan.getBaseCommit()).isEqualTo(BASE_COMMIT);
        assertThat(plan.getTasks()).hasSize(1);
        assertThat(plan.getTasks().getFirst().getType()).isEqualTo(TaskType.CODE_MINOR);
        assertThat(plan.getTasks().getFirst().getTarget()).isEqualTo("src/auth/session.ts");
        assertThat(plan.getTasks().getFirst().getContent().diff()).contains("+b");
    }

    @Test
    void 기준_커밋이_바뀌면_409_EDIT_CONFLICT() throws Exception {
        given(webshellClient.currentCommit(eq(REPOSITORY), any())).willReturn("deadbeef");

        String body = """
                {
                  "baseCommit": "%s",
                  "description": "로그인 타임아웃 긴급 수정",
                  "files": [ { "path": "src/auth/session.ts", "patch": "--- a\\n+++ b\\n" } ]
                }
                """.formatted(BASE_COMMIT);

        mockMvc.perform(post("/api/v1/projects/{projectId}/editor/changes", project.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));

        assertThat(requestRepository.count()).isZero();
    }

    @Test
    void 변경_파일이_비면_400() throws Exception {
        String body = """
                { "baseCommit": "%s", "description": "x", "files": [] }
                """.formatted(BASE_COMMIT);

        mockMvc.perform(post("/api/v1/projects/{projectId}/editor/changes", project.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 에디터_변경_외부망_요청은_403() throws Exception {
        String body = """
                { "baseCommit": "%s", "description": "x",
                  "files": [ { "path": "a", "patch": "p" } ] }
                """.formatted(BASE_COMMIT);

        mockMvc.perform(post("/api/v1/projects/{projectId}/editor/changes", project.getId())
                        .with(external())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
        then(webshellClient).should(never()).currentCommit(any(), any());
    }

    @Test
    void 웹쉘이_없으면_502() throws Exception {
        given(webshellClient.listFiles(any(), any(), any()))
                .willThrow(new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", project.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"));
    }
}
