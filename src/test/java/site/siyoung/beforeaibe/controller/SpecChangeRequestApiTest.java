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
class SpecChangeRequestApiTest {

    private static final String V1_BODY = "# 인증 서비스 기획서\n로그인 실패 시 안내 메시지를 표시한다.";
    private static final String CONTENT = "실패 메시지에 한글·영문 안내를 추가한다.";

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

    private Department dev;
    private Project project;
    private User owner;
    private User manager;
    private User requester;

    @BeforeEach
    void setUp() {
        dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        Department ops = departmentRepository.saveAndFlush(Department.builder().name("운영팀").build());
        owner = saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        manager = saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        saveUser(userRepository, passwordEncoder, "MGR101", Role.DEPT_MANAGER, ops.getId());
        // 다른 부서 직원도 수정 요청을 낼 수 있음
        requester = saveUser(userRepository, passwordEncoder, "EMP101", Role.EMPLOYEE, ops.getId());

        project = saveProject();
        specVersionRepository.saveAndFlush(SpecVersion.builder()
                .project(project)
                .version(1)
                .body(V1_BODY)
                .createdBy(manager)
                .build());
    }

    private Project saveProject() {
        Project newProject = Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build();
        newProject.addOwner(owner);
        newProject.addOwner(manager);
        return projectRepository.saveAndFlush(newProject);
    }

    private String token(String employeeNumber) throws Exception {
        return json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
    }

    private String createChangeRequest(UUID projectId) throws Exception {
        return json(mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests", projectId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP101")))
                        .contentType("application/json")
                        .content("{\"content\": \"%s\", \"reason\": \"다국어 사용자 지원\"}".formatted(CONTENT)))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");
    }

    @Test
    void 수정_요청을_만들면_OPEN이고_기획서는_바뀌지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP101")))
                        .contentType("application/json")
                        .content("{\"content\": \"%s\", \"reason\": \"다국어 사용자 지원\"}".formatted(CONTENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.content").value(CONTENT))
                .andExpect(jsonPath("$.reason").value("다국어 사용자 지원"))
                .andExpect(jsonPath("$.requesterId").value(requester.getId().toString()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());

        mockMvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP101"))))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void 변경_내용이나_사유가_없으면_400을_돌려준다() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP101")))
                        .contentType("application/json")
                        .content("{\"content\": \"%s\"}".formatted(CONTENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 수정_요청_목록을_상태로_걸러_조회한다() throws Exception {
        String accepted = createChangeRequest(project.getId());
        String open = createChangeRequest(project.getId());
        String token = token("MGR001");
        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/accept", project.getId(), accepted)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(open))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"));

        mockMvc.perform(get("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 수정_요청_목록을_커서로_나눠_조회한다() throws Exception {
        createChangeRequest(project.getId());
        createChangeRequest(project.getId());
        createChangeRequest(project.getId());
        String token = token("EMP101");

        String first = mockMvc.perform(get("/api/v1/projects/{id}/spec/change-requests", project.getId())
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/projects/{id}/spec/change-requests", project.getId())
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("limit", "2")
                        .param("cursor", JsonPath.<String>read(first, "$.nextCursor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 담당_부서_팀장이_반영하면_변경_내용으로_새_기획서_버전이_생긴다() throws Exception {
        String crId = createChangeRequest(project.getId());

        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/accept", project.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeRequest.id").value(crId))
                .andExpect(jsonPath("$.changeRequest.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.changeRequest.requesterId").value(requester.getId().toString()))
                .andExpect(jsonPath("$.spec.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.spec.version").value(2))
                .andExpect(jsonPath("$.spec.body").value(CONTENT))
                .andExpect(jsonPath("$.spec.createdBy").value(manager.getId().toString()));

        assertThat(specVersionRepository.findByProjectIdAndVersion(project.getId(), 2).orElseThrow().getReason())
                .isEqualTo("다국어 사용자 지원");
    }

    @Test
    void 반려하면_사유를_남기고_기획서는_바뀌지_않는다() throws Exception {
        String crId = createChangeRequest(project.getId());

        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/reject", project.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001")))
                        .contentType("application/json")
                        .content("{\"reason\": \"현재 릴리스 범위에 포함되지 않습니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(crId))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("다국어 사용자 지원"))
                .andExpect(jsonPath("$.rejectionReason").value("현재 릴리스 범위에 포함되지 않습니다."));

        mockMvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("EMP101"))))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void 반려_사유가_없으면_400을_돌려준다() throws Exception {
        String crId = createChangeRequest(project.getId());

        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/reject", project.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001")))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 이미_처리된_수정_요청은_다시_반영하거나_반려할_수_없다() throws Exception {
        String crId = createChangeRequest(project.getId());
        String token = token("MGR001");
        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/accept", project.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/accept", project.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/reject", project.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"reason\": \"반려\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 직원이나_다른_부서_팀장은_반영할_수_없다() throws Exception {
        String crId = createChangeRequest(project.getId());

        for (String employeeNumber : new String[]{"EMP002", "MGR101"}) {
            mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/accept", project.getId(), crId)
                            .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token(employeeNumber))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        }
    }

    @Test
    void 다른_프로젝트의_수정_요청_ID로는_처리할_수_없다() throws Exception {
        String crId = createChangeRequest(project.getId());
        Project other = saveProject();

        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests/{crId}/accept", other.getId(), crId)
                        .with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token("MGR001"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 수정_요청_API는_외부망에서_쓸_수_없다() throws Exception {
        String token = token("MGR001");

        mockMvc.perform(post("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"content\": \"a\", \"reason\": \"b\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
        mockMvc.perform(get("/api/v1/projects/{id}/spec/change-requests", project.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }
}
