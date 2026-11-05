package site.siyoung.beforeaibe.controller;

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
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.SpecVersionRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class ProjectSpecApiTest {

    private static final String V1_BODY = "# 인증 서비스 기획서\n로그인 실패 시 안내 메시지를 표시한다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SpecVersionRepository specVersionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;
    private User manager;

    @BeforeEach
    void setUp() {
        Department dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        Department ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        User owner = saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        manager = saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        saveUser(userRepository, passwordEncoder, "MGR101", Role.DEPT_MANAGER, ops.getId());
        saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "ADMIN001", Role.ADMIN, null);

        Project newProject = Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build();
        newProject.addOwner(owner);
        newProject.addOwner(manager);
        project = projectRepository.saveAndFlush(newProject);
        specVersionRepository.saveAndFlush(SpecVersion.builder()
                .project(project)
                .version(1)
                .body(V1_BODY)
                .createdBy(manager)
                .build());
    }

    private String token(String employeeNumber) throws Exception {
        return json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
    }

    private MvcResult updateSpec(String token, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andReturn();
    }

    @Test
    void 현재_기획서를_조회한다() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.body").value(V1_BODY))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.createdBy").value(manager.getId().toString()))
                .andExpect(jsonPath("$.restoredFromVersion").doesNotExist());
    }

    @Test
    void 담당_부서_팀장이_수정하면_기존_버전을_두고_새_버전을_저장한다() throws Exception {
        String token = token("MGR001");

        mockMvc.perform(put("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"body\": \"v2 본문\", \"reason\": \"다국어 안내 요구사항 반영\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.body").value("v2 본문"))
                .andExpect(jsonPath("$.createdBy").value(manager.getId().toString()));

        // 사유는 선택 입력
        mockMvc.perform(put("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"body\": \"v3 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        assertThat(specVersionRepository.findByProjectIdAndVersion(project.getId(), 1).orElseThrow().getBody())
                .isEqualTo(V1_BODY);
        assertThat(specVersionRepository.findByProjectIdAndVersion(project.getId(), 2).orElseThrow().getReason())
                .isEqualTo("다국어 안내 요구사항 반영");
    }

    @Test
    void 관리자도_기획서를_수정할_수_있다() throws Exception {
        assertThat(updateSpec(token("ADMIN001"), "{\"body\": \"v2 본문\"}").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void 직원이나_다른_부서_팀장은_기획서를_수정할_수_없다() throws Exception {
        for (String employeeNumber : new String[]{"EMP001", "MGR101"}) {
            mockMvc.perform(put("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                            .header(HttpHeaders.AUTHORIZATION, bearer(token(employeeNumber)))
                            .contentType("application/json")
                            .content("{\"body\": \"v2 본문\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        }
    }

    @Test
    void 본문이_비어_있으면_400을_돌려준다() throws Exception {
        mockMvc.perform(put("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001")))
                        .contentType("application/json")
                        .content("{\"body\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 버전_이력을_최신순으로_커서로_나눠_조회한다() throws Exception {
        String token = token("MGR001");
        updateSpec(token, "{\"body\": \"v2 본문\"}");
        updateSpec(token, "{\"body\": \"v3 본문\"}");

        MvcResult first = mockMvc.perform(get("/api/v1/projects/{id}/spec/versions", project.getId())
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].body").value("v3 본문"))
                .andExpect(jsonPath("$.items[1].version").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();

        mockMvc.perform(get("/api/v1/projects/{id}/spec/versions", project.getId())
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("limit", "2")
                        .param("cursor", json(first, "$.nextCursor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 잘못된_커서면_400을_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/spec/versions", project.getId())
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token("EMP001")))
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 이전_버전을_복원하면_그_내용으로_새_버전을_만든다() throws Exception {
        String token = token("MGR001");
        updateSpec(token, "{\"body\": \"v2 본문\"}");

        // 본문 없이 호출
        mockMvc.perform(post("/api/v1/projects/{id}/spec/versions/{version}/restore", project.getId(), 1)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.body").value(V1_BODY))
                .andExpect(jsonPath("$.restoredFromVersion").value(1));

        // 사유와 함께 호출
        mockMvc.perform(post("/api/v1/projects/{id}/spec/versions/{version}/restore", project.getId(), 2)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"reason\": \"v2로 되돌림\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.body").value("v2 본문"))
                .andExpect(jsonPath("$.restoredFromVersion").value(2));

        SpecVersion restored = specVersionRepository.findByProjectIdAndVersion(project.getId(), 4).orElseThrow();
        assertThat(restored.getReason()).isEqualTo("v2로 되돌림");
        assertThat(restored.getRestoredFromVersion()).isEqualTo(2);
    }

    @Test
    void 없는_버전을_복원하면_404를_돌려준다() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{id}/spec/versions/{version}/restore", project.getId(), 9)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 없는_프로젝트의_기획서는_404를_돌려준다() throws Exception {
        String token = token("MGR001");

        mockMvc.perform(get("/api/v1/projects/{id}/spec", UUID.randomUUID()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/projects/{id}/spec", UUID.randomUUID()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"body\": \"v2 본문\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 기획서_수정과_복원은_외부망에서_할_수_없다() throws Exception {
        String token = token("MGR001");

        mockMvc.perform(put("/api/v1/projects/{id}/spec", project.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"body\": \"v2 본문\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
        mockMvc.perform(post("/api/v1/projects/{id}/spec/versions/{version}/restore", project.getId(), 1)
                        .with(external()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }
}
