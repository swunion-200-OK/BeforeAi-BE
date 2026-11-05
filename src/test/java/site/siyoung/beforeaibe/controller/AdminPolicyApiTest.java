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
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveAdmin;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminPolicyApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken() throws Exception {
        saveAdmin(userRepository, passwordEncoder, "ADMIN01");
        return json(loginInternal(mockMvc, "ADMIN01"), "$.accessToken");
    }

    private String applyPresetBody(String preset) {
        return """
                {"preset": "%s", "adminProof": {"keyId": "%s", "signature": "sig-example"}}
                """.formatted(preset, UUID.randomUUID());
    }

    @Test
    void 프리셋을_적용하면_정책_버전이_증가하고_웹쉘에_배포된다() throws Exception {
        String token = adminToken();

        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(applyPresetBody("MEDIUM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("MEDIUM"))
                .andExpect(jsonPath("$.policyVersion").value(1))
                .andExpect(jsonPath("$.webshellApplied").value(true));

        // 두 번째 적용은 버전이 2로 증가
        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(applyPresetBody("LARGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(2));
    }

    @Test
    void 현재_정책을_조회한다() throws Exception {
        String token = adminToken();
        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(applyPresetBody("MEDIUM")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/policy").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("MEDIUM"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.approvalMatrix").isArray())
                .andExpect(jsonPath("$.policyHash").isNotEmpty());
    }

    @Test
    void 승인_기준표를_수정하면_새_버전이_생성된다() throws Exception {
        String token = adminToken();
        // 먼저 프리셋으로 version 1 생성
        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(applyPresetBody("MEDIUM")))
                .andExpect(status().isOk());

        String body = """
                {
                  "rules": [
                    {"taskType": "DEPLOY", "risk": "HIGH",
                     "requiredApprovers": [{"rule": "PROJECT_OWNER", "count": 1}]}
                  ],
                  "adminProof": {"keyId": "%s", "signature": "sig-example"}
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(put("/api/v1/admin/approval-matrix").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(2))
                .andExpect(jsonPath("$.webshellApplied").value(true))
                .andExpect(jsonPath("$.rules[0].taskType").value("DEPLOY"));
    }

    @Test
    void adminProof_없이_프리셋을_적용하면_400() throws Exception {
        String token = adminToken();
        String body = """
                {"preset": "MEDIUM"}
                """;

        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 승인_기준표는_모든_로그인_사용자가_조회할_수_있다() throws Exception {
        String token = adminToken();
        mockMvc.perform(put("/api/v1/admin/policy/preset").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content(applyPresetBody("MEDIUM")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/approval-matrix").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules").isArray());
    }
}
