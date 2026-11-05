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
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.ed25519PublicKey;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveAdmin;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminMiscApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SigningKeyRepository signingKeyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;

    private String adminToken() throws Exception {
        admin = saveAdmin(userRepository, passwordEncoder, "ADMIN01");
        return json(loginInternal(mockMvc, "ADMIN01"), "$.accessToken");
    }

    private String adminProofBody() {
        return """
                {"adminProof": {"keyId": "%s", "signature": "sig-example"}}
                """.formatted(UUID.randomUUID());
    }

    // --- 부서 ---

    @Test
    void 부서를_생성하고_목록으로_조회한다() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/v1/admin/departments").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"name\": \"개발팀\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("개발팀"));

        mockMvc.perform(get("/api/v1/departments").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("개발팀"));
    }

    @Test
    void 중복된_부서명으로_생성하면_409() throws Exception {
        String token = adminToken();
        mockMvc.perform(post("/api/v1/admin/departments").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"name\": \"개발팀\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/departments").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"name\": \"개발팀\"}"))
                .andExpect(status().isConflict());
    }

    // --- 서명 키 ---

    @Test
    void 대기_중인_공개키를_등록하면_REGISTERED가_된다() throws Exception {
        String token = adminToken();
        SigningKey key = signingKeyRepository.saveAndFlush(SigningKey.builder()
                .user(admin)
                .algorithm("Ed25519")
                .publicKey(ed25519PublicKey())
                .deviceName("노트북")
                .status(SigningKeyStatus.PENDING)
                .build());

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").value(key.getId().toString()))
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    @Test
    void 공개키를_폐기하면_204() throws Exception {
        String token = adminToken();
        SigningKey key = signingKeyRepository.saveAndFlush(SigningKey.builder()
                .user(admin)
                .algorithm("Ed25519")
                .publicKey(ed25519PublicKey())
                .deviceName("노트북")
                .status(SigningKeyStatus.REGISTERED)
                .build());

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
    }

    @Test
    void 서명키_목록을_조회한다() throws Exception {
        String token = adminToken();
        signingKeyRepository.saveAndFlush(SigningKey.builder()
                .user(admin)
                .algorithm("Ed25519")
                .publicKey(ed25519PublicKey())
                .deviceName("노트북")
                .status(SigningKeyStatus.PENDING)
                .build());

        mockMvc.perform(get("/api/v1/admin/signing-keys").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].userId").value(admin.getId().toString()));
    }

    // --- kill-all ---

    @Test
    void 전사_실행을_일괄_중단한다() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/v1/admin/kill-all").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"reason\": \"긴급 중단\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KILLED"))
                .andExpect(jsonPath("$.executionIds").isArray());
    }

    @Test
    void kill_all은_외부망에서도_허용된다() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/v1/admin/kill-all").with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"reason\": \"긴급 중단\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KILLED"));
    }
}
