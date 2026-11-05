package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.Task;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;
import site.siyoung.beforeaibe.entity.enums.PlanKind;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.entity.value.TaskContent;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ExecutionRepository;
import site.siyoung.beforeaibe.repository.PlanRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.webshell.WebshellClient;
import site.siyoung.beforeaibe.webshell.PolicyDeploymentClient;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    // 공개키 등록·폐기는 새 정책 버전으로 배포됨. 기본은 테스트 설정의 정책 배포 스텁(항상 성공)을 쓰고,
    // 배포 실패나 배포한 정책 묶음 확인이 필요한 테스트에서만 결과를 바꾸거나 인자를 확인함
    @MockitoSpyBean
    private PolicyDeploymentClient policyDeploymentClient;

    // 전사 중단은 실제 웹쉘 호출(WebshellClient.killAll)이라 테스트에서는 응답을 지정함
    @MockitoBean
    private WebshellClient webshellClient;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private ExecutionRepository executionRepository;

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

    // 정책 버전 1을 만듦(규모 프리셋 적용). 공개키 등록·폐기는 정책이 있어야 새 버전으로 배포됨
    private void applyPreset(String token) throws Exception {
        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"preset": "MEDIUM", "adminProof": {"keyId": "%s", "signature": "sig-example"}}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    // 마지막으로 배포한 정책 묶음의 signingKeys[].keyId
    @SuppressWarnings("unchecked")
    private List<String> deployedSigningKeyIds() {
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        then(policyDeploymentClient).should(atLeastOnce()).apply(payload.capture(), any(), anyString());
        List<Map<String, Object>> keys = (List<Map<String, Object>>) payload.getValue().get("signingKeys");
        return keys.stream().map(key -> (String) key.get("keyId")).toList();
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
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 정책이_있으면_공개키_등록은_새_정책_버전으로_배포되고_묶음에_그_키가_들어간다() throws Exception {
        String token = adminToken();
        applyPreset(token);
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED, rawPublicKey(adminKeyPair));
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.policyVersion").value(2));

        assertThat(deployedSigningKeyIds()).contains(key.getId().toString(), adminKey.getId().toString());
    }

    @Test
    void 정책이_없으면_공개키만_등록되고_배포하지_않는다() throws Exception {
        String token = adminToken();
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.PENDING, rawPublicKey(adminKeyPair));

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", adminKey.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, adminKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(0));

        then(policyDeploymentClient).should(never()).apply(any(), any(), any());
    }

    @Test
    void 등록된_공개키를_폐기하면_그_키를_뺀_새_정책_버전이_배포된다() throws Exception {
        String token = adminToken();
        applyPreset(token);
        SigningKey key = saveKey(admin, SigningKeyStatus.REGISTERED);

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isNoContent());

        assertThat(deployedSigningKeyIds()).doesNotContain(key.getId().toString());
        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.REVOKED);
    }

    @Test
    void 등록된_공개키를_adminProof_없이_폐기하면_400이고_상태는_그대로다() throws Exception {
        String token = adminToken();
        SigningKey key = saveKey(admin, SigningKeyStatus.REGISTERED);

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.REGISTERED);
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
    void 정책_배포에_실패하면_502이고_등록은_저장된_채_mutationCommitted가_true다() throws Exception {
        String token = adminToken();
        applyPreset(token);
        KeyPair adminKeyPair = ed25519KeyPair();
        SigningKey adminKey = saveKey(admin, SigningKeyStatus.REGISTERED, rawPublicKey(adminKeyPair));
        SigningKey key = saveKey(employee(), SigningKeyStatus.PENDING);
        willThrow(new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE))
                .given(policyDeploymentClient).apply(any(), any(), any());

        mockMvc.perform(post("/api/v1/admin/signing-keys/{keyId}/register", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(registerProofBody(adminKey, adminKeyPair, key)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.mutationCommitted").value(true));

        // 정책 재전송이 다시 배포하므로 키는 등록된 상태로 남음
        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.REGISTERED);
    }

    @Test
    void 폐기한_정책_배포에_실패하면_502이고_폐기는_저장된_채_mutationCommitted가_true다() throws Exception {
        String token = adminToken();
        applyPreset(token);
        SigningKey key = saveKey(admin, SigningKeyStatus.REGISTERED);
        willThrow(new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE))
                .given(policyDeploymentClient).apply(any(), any(), any());

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(adminProofBody()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.mutationCommitted").value(true));

        assertThat(signingKeyRepository.findById(key.getId()).orElseThrow().getStatus())
                .isEqualTo(SigningKeyStatus.REVOKED);
    }

    @Test
    void 등록_대기_공개키는_관리자_서명과_정책_배포_없이_폐기된다() throws Exception {
        String token = adminToken();
        applyPreset(token);
        SigningKey key = saveKey(admin, SigningKeyStatus.PENDING);
        clearInvocations(policyDeploymentClient);

        mockMvc.perform(delete("/api/v1/admin/signing-keys/{keyId}", key.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        then(policyDeploymentClient).should(never()).apply(any(), any(), any());
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
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType("application/json").content(adminProofBody()))
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
        given(webshellClient.killAll("긴급 중단"))
                .willReturn(new WebshellClient.KillAllResult(List.of(), "KILLED", "c2ln"));

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
        given(webshellClient.killAll("긴급 중단"))
                .willReturn(new WebshellClient.KillAllResult(List.of(), "KILLED", "c2ln"));

        mockMvc.perform(post("/api/v1/admin/kill-all").with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"reason\": \"긴급 중단\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KILLED"));
    }

    @Test
    void kill_all은_웹쉘이_중단한_실행을_KILLED로_바꾸고_시작하지_않은_작업을_SKIPPED로_바꾼다() throws Exception {
        String token = adminToken();
        Execution execution = saveRunningExecution();
        given(webshellClient.killAll("긴급 중단")).willReturn(new WebshellClient.KillAllResult(
                List.of(execution.getWebshellExecutionId()), "KILLED", "c2ln"));

        mockMvc.perform(post("/api/v1/admin/kill-all").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"reason\": \"긴급 중단\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionIds[0]").value(execution.getWebshellExecutionId().toString()));

        Execution found = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.KILLED);
        assertThat(found.getKillReason()).isEqualTo("긴급 중단");
        assertThat(requestRepository.findById(found.getRequest().getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.KILLED);
        assertThat(planRepository.findWithTasksById(found.getPlan().getId()).orElseThrow().getTasks())
                .extracting(Task::getStatus)
                .containsExactly(TaskStatus.SUCCEEDED, TaskStatus.SKIPPED);
    }

    @Test
    void kill_all은_실행_중이던_작업을_FAILED로_시작_전_작업을_SKIPPED로_바꾼다() throws Exception {
        String token = adminToken();
        Execution execution = saveRunningExecution(TaskStatus.SUCCEEDED, TaskStatus.RUNNING, TaskStatus.APPROVED);
        given(webshellClient.killAll("긴급 중단")).willReturn(new WebshellClient.KillAllResult(
                List.of(execution.getWebshellExecutionId()), "KILLED", "c2ln"));

        mockMvc.perform(post("/api/v1/admin/kill-all").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"reason\": \"긴급 중단\"}"))
                .andExpect(status().isOk());

        assertThat(planRepository.findWithTasksById(execution.getPlan().getId()).orElseThrow().getTasks())
                .extracting(Task::getStatus)
                .containsExactly(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.SKIPPED);
    }

    @Test
    void kill_all에서_웹쉘에_연결할_수_없으면_502이고_실행_상태는_그대로다() throws Exception {
        String token = adminToken();
        Execution execution = saveRunningExecution();
        given(webshellClient.killAll(any())).willThrow(new ApiException(ErrorCode.WEBSHELL_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/admin/kill-all").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"reason\": \"긴급 중단\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("WEBSHELL_UNAVAILABLE"));

        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.RUNNING);
    }

    // 실행 중인 요청 + 현재 Plan(성공한 작업 1개, 시작 전 작업 1개) + 실행 세션
    private Execution saveRunningExecution() {
        return saveRunningExecution(TaskStatus.SUCCEEDED, TaskStatus.APPROVED);
    }

    private Execution saveRunningExecution(TaskStatus... taskStatuses) {
        Department dept = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        User requester = saveUser(userRepository, passwordEncoder, "EMP001", false);
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스")
                .department(dept)
                .repository("git@internal:auth-service.git")
                .build());
        Request request = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId())
                .requesterId(requester.getId())
                .prompt("스테이징에 배포해줘")
                .source(RequestSource.PROMPT)
                .status(RequestStatus.RUNNING)
                .analysisAttempts(1)
                .build());
        Plan plan = Plan.builder()
                .request(request)
                .kind(PlanKind.NORMAL)
                .repository("git@internal:auth-service.git")
                .baseCommit("9b2f4e1")
                .build();
        for (int i = 0; i < taskStatuses.length; i++) {
            plan.addTask(task(i + 1, taskStatuses[i]));
        }
        plan = planRepository.saveAndFlush(plan);
        ReflectionTestUtils.setField(request, "currentPlanId", plan.getId());
        requestRepository.saveAndFlush(request);
        return executionRepository.saveAndFlush(Execution.builder()
                .request(request)
                .plan(plan)
                .webshellExecutionId(UUID.randomUUID())
                .planHash("sha256:4be1")
                .status(ExecutionStatus.RUNNING)
                .webshellSignature("c2ln")
                .build());
    }

    private static Task task(int order, TaskStatus status) {
        return Task.builder()
                .order(order)
                .type(TaskType.CODE_FEATURE)
                .risk(Risk.MEDIUM)
                .title("로그인 실패 메시지 변경 " + order)
                .target("src/auth/login.ts")
                .content(new TaskContent("--- a/src/auth/login.ts\n+++ b/src/auth/login.ts", null, null, null))
                .relatedDepartments(List.of())
                .requiredApprovers(List.of(new RequiredApprover(ApproverRule.ANYONE, 1, null)))
                .status(status)
                .build();
    }
}
