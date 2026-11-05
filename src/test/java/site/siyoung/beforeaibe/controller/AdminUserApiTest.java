package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveAdmin;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminUserApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken() throws Exception {
        saveAdmin(userRepository, passwordEncoder, "ADMIN01");
        return json(loginInternal(mockMvc, "ADMIN01"), "$.accessToken");
    }

    @Test
    void 관리자가_계정을_생성하면_초기_비밀번호가_발급된다() throws Exception {
        String token = adminToken();
        Department dept = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());

        String body = """
                {"employeeNumber": "EMP100", "name": "김직원", "departmentId": "%s", "role": "EMPLOYEE"}
                """.formatted(dept.getId());

        mockMvc.perform(post("/api/v1/admin/users").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.employeeNumber").value("EMP100"))
                .andExpect(jsonPath("$.user.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.user.active").value(true))
                .andExpect(jsonPath("$.initialPassword").isNotEmpty());
    }

    @Test
    void 중복된_사번으로_계정을_생성하면_409() throws Exception {
        String token = adminToken();
        saveUser(userRepository, passwordEncoder, "EMP100", false);

        String body = """
                {"employeeNumber": "EMP100", "name": "김직원", "role": "EMPLOYEE"}
                """;

        mockMvc.perform(post("/api/v1/admin/users").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void 계정_목록을_역할로_필터링해_조회한다() throws Exception {
        String token = adminToken();
        saveUser(userRepository, passwordEncoder, "EMP100", false);

        mockMvc.perform(get("/api/v1/admin/users").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("role", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 계정의_역할과_활성_상태를_변경한다() throws Exception {
        String token = adminToken();
        var target = saveUser(userRepository, passwordEncoder, "EMP100", false);

        String body = """
                {"role": "DEPT_MANAGER", "active": false}
                """;

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", target.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DEPT_MANAGER"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void 계정_비밀번호를_초기화하면_새_초기_비밀번호가_발급된다() throws Exception {
        String token = adminToken();
        var target = saveUser(userRepository, passwordEncoder, "EMP100", false);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/reset-password", target.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target.getId().toString()))
                .andExpect(jsonPath("$.initialPassword").isNotEmpty());
    }

    @Test
    void 일반_사용자는_관리자_API에_접근할_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP100", false);
        String token = json(loginInternal(mockMvc, "EMP100"), "$.accessToken");

        mockMvc.perform(get("/api/v1/admin/users").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 외부망에서_계정_생성을_호출하면_403() throws Exception {
        String token = adminToken();

        String body = """
                {"employeeNumber": "EMP100", "name": "김직원", "role": "EMPLOYEE"}
                """;

        mockMvc.perform(post("/api/v1/admin/users").with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }
}
