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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ProjectApiTest {

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

    private Department dev;
    private User owner1;
    private User owner2;

    @BeforeEach
    void setUp() {
        dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        owner1 = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        owner2 = saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        saveUser(userRepository, passwordEncoder, "ADMIN001", Role.ADMIN, null);
    }

    private String token(String employeeNumber) throws Exception {
        return json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
    }

    private String createBody(UUID departmentId, UUID... ownerIds) {
        String owners = String.join(", ", Arrays.stream(ownerIds).map(id -> "\"" + id + "\"").toList());
        return """
                {
                  "name": "인증 서비스",
                  "departmentId": "%s",
                  "repository": "git@internal:auth-service.git",
                  "ownerIds": [%s],
                  "initialSpec": {"body": "# 인증 서비스\\n로그인 기능 요구사항"}
                }
                """.formatted(departmentId, owners);
    }

    private Project saveProject(String name) {
        Project project = Project.builder()
                .name(name)
                .department(dev)
                .repository("git@internal:" + name + ".git")
                .build();
        project.addOwner(owner1);
        project.addOwner(owner2);
        return projectRepository.saveAndFlush(project);
    }

    @Test
    void 관리자가_프로젝트와_초기_기획서를_만든다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content(createBody(dev.getId(), owner1.getId(), owner2.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("인증 서비스"))
                .andExpect(jsonPath("$.departmentId").value(dev.getId().toString()))
                .andExpect(jsonPath("$.repository").value("git@internal:auth-service.git"))
                .andExpect(jsonPath("$.owners.length()").value(2))
                .andExpect(jsonPath("$.owners[0].id").value(owner1.getId().toString()))
                .andExpect(jsonPath("$.owners[0].name").value("홍길동"))
                .andExpect(jsonPath("$.owners[1].id").value(owner2.getId().toString()))
                .andReturn();
        String projectId = json(result, "$.id");

        mockMvc.perform(get("/api/v1/projects/{id}/spec", projectId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.body").value("# 인증 서비스\n로그인 기능 요구사항"));
    }

    @Test
    void 서로_다른_담당자가_2명_미만이면_409_OWNER_MINIMUM을_돌려준다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content(createBody(dev.getId(), owner1.getId(), owner1.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OWNER_MINIMUM"));
    }

    @Test
    void 존재하지_않는_부서나_담당자로_만들면_400을_돌려준다() throws Exception {
        String token = token("ADMIN001");

        mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content(createBody(UUID.randomUUID(), owner1.getId(), owner2.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content(createBody(dev.getId(), owner1.getId(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 담당_부서_소속이_아닌_담당자로_만들면_400을_돌려준다() throws Exception {
        Department ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        User other = saveUser(userRepository, passwordEncoder, "EMP101", Role.EMPLOYEE, ops.getId());

        mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content(createBody(dev.getId(), owner1.getId(), other.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 필수_값이_없으면_400을_돌려준다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content("{\"name\": \"인증 서비스\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 관리자가_아니면_프로젝트를_만들_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/projects").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001")))
                        .contentType("application/json")
                        .content(createBody(dev.getId(), owner1.getId(), owner2.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 외부망에서는_프로젝트를_만들_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/projects").with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content(createBody(dev.getId(), owner1.getId(), owner2.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 프로젝트_상세를_담당자와_함께_조회한다() throws Exception {
        Project project = saveProject("auth");

        mockMvc.perform(get("/api/v1/projects/{id}", project.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(project.getId().toString()))
                .andExpect(jsonPath("$.name").value("auth"))
                .andExpect(jsonPath("$.departmentId").value(dev.getId().toString()))
                .andExpect(jsonPath("$.repository").value("git@internal:auth.git"))
                .andExpect(jsonPath("$.owners.length()").value(2));
    }

    @Test
    void 없는_프로젝트를_조회하면_404를_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}", UUID.randomUUID()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP001"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 프로젝트_목록을_커서로_나눠_전부_조회한다() throws Exception {
        List<String> saved = List.of(saveProject("a").getId().toString(), saveProject("b").getId().toString(),
                saveProject("c").getId().toString());
        String token = token("EMP001");

        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            var request = get("/api/v1/projects").with(internal())
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .param("limit", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(lessThanOrEqualTo(2)))
                    .andReturn().getResponse().getContentAsString();
            ids.addAll(JsonPath.read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertThat(ids).doesNotHaveDuplicates().containsAll(saved);
    }

    @Test
    void 관리자가_담당자를_교체한다() throws Exception {
        Project project = saveProject("owners-replace");
        User newOwner = saveUser(userRepository, passwordEncoder, "EMP777", Role.EMPLOYEE, dev.getId());

        mockMvc.perform(put("/api/v1/projects/{id}/owners", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content("""
                                {"ownerIds": ["%s", "%s"]}""".formatted(owner1.getId(), newOwner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners.length()").value(2))
                .andExpect(jsonPath("$.owners[1].id").value(newOwner.getId().toString()));
    }

    @Test
    void 담당자가_2명_미만이거나_권한이_없으면_교체할_수_없다() throws Exception {
        Project project = saveProject("owners-invalid");

        mockMvc.perform(put("/api/v1/projects/{id}/owners", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN001")))
                        .contentType("application/json")
                        .content("""
                                {"ownerIds": ["%s"]}""".formatted(owner1.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OWNER_MINIMUM"));
        mockMvc.perform(put("/api/v1/projects/{id}/owners", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP001")))
                        .contentType("application/json")
                        .content("""
                                {"ownerIds": ["%s", "%s"]}""".formatted(owner1.getId(), owner2.getId())))
                .andExpect(status().isForbidden());
    }
}
