package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

// 커밋 후 전이는 테스트 트랜잭션 안에서는 실행되지 않으므로 @Transactional 없이 실제 커밋까지 검증함
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EditorReviewFlowTest {

    private static final String BASE_COMMIT = "9b2f4e1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private WebshellClient webshellClient;

    private Department department;
    private Project project;
    private User requester;
    private User colleague;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("update requests set current_plan_id = null where project_id = ?", project.getId());
        jdbcTemplate.update("delete from tasks where plan_id in (select p.id from plans p join requests r on r.id = p.request_id where r.project_id = ?)", project.getId());
        jdbcTemplate.update("delete from plans where request_id in (select id from requests where project_id = ?)", project.getId());
        jdbcTemplate.update("delete from requests where project_id = ?", project.getId());
        jdbcTemplate.update("delete from project_owners where project_id = ?", project.getId());
        jdbcTemplate.update("delete from projects where id = ?", project.getId());
        for (User user : new User[]{requester, colleague}) {
            jdbcTemplate.update("delete from refresh_tokens where user_id = ?", user.getId());
            jdbcTemplate.update("delete from users where id = ?", user.getId());
        }
        jdbcTemplate.update("delete from departments where id = ?", department.getId());
    }

    @Test
    void 에디터_변경은_202_ANALYZING으로_응답하고_커밋_후_REVIEWING이_되어_제출할_수_있다() throws Exception {
        department = departmentRepository.saveAndFlush(Department.builder().name("에디터팀").build());
        requester = saveUser(userRepository, passwordEncoder, "EDIT001", Role.EMPLOYEE, department.getId());
        colleague = saveUser(userRepository, passwordEncoder, "EDIT002", Role.EMPLOYEE, department.getId());
        project = projectRepository.saveAndFlush(Project.builder().name("에디터")
                .department(department).repository("git@internal:editor.git").build());
        given(webshellClient.currentCommit(any(), any())).willReturn(BASE_COMMIT);
        String token = bearer(json(loginInternal(mockMvc, "EDIT001"), "$.accessToken"));

        String requestId = json(mockMvc.perform(post("/api/v1/projects/{id}/editor/changes", project.getId())
                        .with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType("application/json")
                        .content("""
                                {"baseCommit": "%s", "description": "타임아웃 수정",
                                 "files": [{"path": "a.ts", "patch": "--- a/a\\n+++ b/a\\n@@ -1 +1 @@\\n-a\\n+b\\n"}]}"""
                                .formatted(BASE_COMMIT)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ANALYZING"))
                .andReturn(), "$.requestId");

        mockMvc.perform(get("/api/v1/requests/{id}", requestId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.currentPlan.tasks[0].requiredApprovers[0].rule").value("SAME_DEPT_OTHER"))
                .andExpect(jsonPath("$.currentPlan.tasks[0].requiredApprovers[0].candidates[0]").value(colleague.getId().toString()));

        mockMvc.perform(post("/api/v1/requests/{id}/submit", requestId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }
}
