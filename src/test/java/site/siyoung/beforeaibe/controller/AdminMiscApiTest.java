package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.webshell.WebshellPolicyClient;

import java.security.KeyPair;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.ed25519KeyPair;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.ed25519PublicKey;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.rawPublicKey;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveAdmin;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.sign;

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

    // 기본은 스텁(항상 성공) 동작을 그대로 쓰고, 웹쉘 반영 실패가 필요한 테스트에서만 결과를 바꿈
    @MockitoSpyBean
    private WebshellPolicyClient webshellPolicyClient;

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

    private SigningKey saveKey(User owner, SigningKeyStatus keyStatus) throws Exception {
        return saveKey(owner, keyStatus, ed25519PublicKey());
    }

    private SigningKey saveKey(User owner, SigningKeyStatus keyStatus, String publicKey) {
        return signingKeyRepository.saveAndFlush(SigningKey.builder()
                .user(owner)
                .algorithm("Ed25519")
                .publicKey(publicKey)
                .deviceName("노트북")
                .status(keyStatus)
                .build());
    }

    // 관리자 기기가 등록 대상 키에 대해 만드는 서명. 서버의 서명 대상 문자열과 같은 규칙으로 만든다
    private String registerProofBody(UUID proofKeyId, KeyPair proofKeyPair, String targetKeyId, String targetPublicKey)
            throws Exception {
        String message = "beforeai:register-signing-key:" + targetKeyId + ":" + targetPublicKey;
        return """
                {"adminProof": {"keyId": "%s", "signature": "%s"}}
                """.formatted(proofKeyId, sign(proofKeyPair, message));
    }

    private String registerProofBody(SigningKey proofKey, KeyPair proofKeyPair, SigningKey target) throws Exception {
        return registerProofBody(proofKey.getId(), proofKeyPair, target.getId().toString(), target.getPublicKey());
    }

    private User employee() {
        return saveUser(userRepository, passwordEncoder, "EMP001", false);
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
    void 관리자_본인의_등록된_키로_서명하면_대기_중인_공개키가_REGISTERED가_된다() throws Exception {
        String token = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED, rawPublicKey(adminKeyPair));
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").value(key.getId().toString()))
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    @Test
    void 첫_관리자_키는_그_키_자신의_서명으로_등록할_수_있다() throws Exception {
        String token = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.PENDING, rawPublicKey(adminKeyPair));

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", adminKey.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, adminKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    @Test
    void 다른_사용자의_키는_그_키_자신의_서명으로_등록할_수_없다() throws Exception {
        String token = adminToken();
        KeyPair employeeKeyPair = ed25519KeyPair();
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING, rawPublicKey(employeeKeyPair));

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(key, employeeKeyPair, key)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("KEY_NOT_REGISTERED"));
    }

    @Test
    void 등록되지_않은_관리자_키로_서명하면_403_KEY_NOT_REGISTERED() throws Exception {
        String token = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.PENDING, rawPublicKey(adminKeyPair));
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, key)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("KEY_NOT_REGISTERED"));
    }

    @Test
    void 다른_내용에_서명했거나_다른_개인키로_서명하면_403_FORBIDDEN_ROLE() throws Exception {
        String token = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED, rawPublicKey(adminKeyPair));
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);

        // 등록 대상 공개키를 바꿔 서명
        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content(registerProofBody(adminKey.getId(), adminKeyPair, key.getId().toString(), ed25519PublicKey())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));

        // 등록된 키와 다른 개인키로 서명
        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, ed25519KeyPair(), key)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));

        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.PENDING);
    }

    @Test
    void 서명_형식이_잘못되면_400() throws Exception {
        String token = adminToken();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED);
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"adminProof\": {\"keyId\": \"%s\", \"signature\": \"sig-example\"}}"
                                .formatted(adminKey.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
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

    @Test
    void 등록_대기가_아닌_공개키를_등록하면_409() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.REGISTERED);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void adminProof_없이_등록하면_400() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.PENDING);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 없는_공개키를_등록하거나_폐기하면_404() throws Exception {
        String token = adminToken();
        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", unknownId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", unknownId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 웹쉘_등록에_실패하면_502이고_상태는_PENDING으로_남는다() throws Exception {
        String token = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED, rawPublicKey(adminKeyPair));
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);
        willReturn(false).given(webshellPolicyClient).registerSigningKey(any(), any());

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, key)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"));

        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.PENDING);
    }

    @Test
    void 웹쉘_폐기에_실패하면_502이고_상태는_REGISTERED로_남는다() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.REGISTERED);
        willReturn(false).given(webshellPolicyClient).revokeSigningKey(any());

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"));

        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.REGISTERED);
    }

    @Test
    void 등록_대기_공개키는_웹쉘_호출_없이_폐기된다() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.PENDING);

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        then(webshellPolicyClient).should(never()).revokeSigningKey(any());
        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.REVOKED);
    }

    @Test
    void 이미_폐기된_공개키를_다시_폐기하면_409() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.REVOKED);

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 기기_키를_폐기하면_같은_사용자가_새_기기_키를_제출할_수_있다() throws Exception {
        String adminToken = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED, rawPublicKey(adminKeyPair));
        employee();
        String userToken = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        String oldPublicKey = ed25519PublicKey();
        String keyId = json(mockMvc.perform(post("/api/v1/me/signing-keys").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\", \"deviceName\": \"이전 PC\"}"
                                .formatted(oldPublicKey)))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", keyId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType("application/json")
                        .content(registerProofBody(adminKey.getId(), adminKeyPair, keyId, oldPublicKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", keyId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\", \"deviceName\": \"새 PC\"}"
                                .formatted(ed25519PublicKey())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void ADMIN이_아니면_서명키_관리_API를_쓸_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(get("/api/v1/admin/signing-keys").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 외부망에서는_서명키_관리_API를_쓸_수_없다() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.PENDING);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
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
