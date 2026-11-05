package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.Base64;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.PASSWORD;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.ed25519PublicKey;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.otpCode;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.refreshCookie;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class MeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void 내_정보와_현재_접속_환경을_조회한다() throws Exception {
        User user = saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(get("/api/v1/me").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.employeeNumber").value("EMP001"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.network").value("INTERNAL"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andExpect(jsonPath("$.hasRegisteredSigningKey").value(false));
    }

    @Test
    void 비밀번호를_바꾸기_전에는_다른_API를_쓸_수_없고_바꾼_뒤_재발급받으면_쓸_수_있다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", true);
        MvcResult login = loginInternal(mockMvc, "EMP001");
        String token = json(login, "$.accessToken");

        mockMvc.perform(get("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        mockMvc.perform(put("/api/v1/me/password").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"currentPassword": "%s", "newPassword": "new-password-2@"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").with(internal()).cookie(refreshCookie(login)))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/v1/me/signing-keys").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(json(refreshed, "$.accessToken"))))
                .andExpect(status().isOk());
    }

    @Test
    void 현재_비밀번호가_틀리면_400_VALIDATION_ERROR를_돌려준다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", true);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(put("/api/v1/me/password").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"currentPassword\": \"wrong\", \"newPassword\": \"new-password-2@\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 내부망_전용_API를_외부망에서_부르면_403_INTERNAL_NETWORK_ONLY를_돌려준다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        List<MockHttpServletRequestBuilder> internalOnly = List.of(
                put("/api/v1/me/password").contentType("application/json")
                        .content("{\"currentPassword\": \"a\", \"newPassword\": \"b\"}"),
                post("/api/v1/me/otp").contentType("application/json").content("{}"),
                post("/api/v1/me/signing-keys").contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"a\"}"),
                get("/api/v1/webshell/public-key"));

        for (MockHttpServletRequestBuilder request : internalOnly) {
            mockMvc.perform(request.with(external()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"))
                    .andExpect(jsonPath("$.error.message").value("내부망에서만 가능한 기능입니다"));
        }
    }

    @Test
    void OTP는_비밀키를_발급받고_코드로_확인하면_등록되며_다시_등록할_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        MvcResult issued = mockMvc.perform(post("/api/v1/me/otp").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").isNotEmpty())
                .andExpect(jsonPath("$.verified").value(false))
                .andReturn();
        String secret = json(issued, "$.secret");

        mockMvc.perform(post("/api/v1/me/otp").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"otpCode\": \"000000x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/me/otp").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{\"otpCode\": \"%s\"}".formatted(otpCode(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.secret").doesNotExist());

        mockMvc.perform(post("/api/v1/me/otp").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 서명_공개키는_PENDING으로_저장되고_사용자당_하나만_제출할_수_있다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");
        String publicKey = ed25519PublicKey();

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"algorithm": "Ed25519", "publicKey": "%s", "deviceName": "업무용 노트북"}
                                """.formatted(publicKey)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.algorithm").value("Ed25519"))
                .andExpect(jsonPath("$.publicKey").value(publicKey))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\"}".formatted(ed25519PublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // 외부망에서도 목록은 조회 가능
        mockMvc.perform(get("/api/v1/me/signing-keys").with(external()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 형식이_잘못된_공개키와_범위를_넘는_limit은_400을_돌려준다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"bm90LWEta2V5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"RSA\", \"publicKey\": \"%s\"}".formatted(ed25519PublicKey())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/me/signing-keys").param("limit", "101").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/me/signing-keys").param("cursor", "broken").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest());
    }


    @Test
    void 다른_사용자가_이미_제출한_공개키는_제출할_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        saveUser(userRepository, passwordEncoder, "EMP002", false);
        String firstToken = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");
        String secondToken = json(loginInternal(mockMvc, "EMP002"), "$.accessToken");
        String body = "{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\"}".formatted(ed25519PublicKey());

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(firstToken))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(secondToken))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 패딩이_없거나_길이가_32바이트가_아닌_공개키와_긴_기기_이름은_400을_돌려준다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");
        String unpadded = ed25519PublicKey().replace("=", "");
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        String longKey = Base64.getEncoder().encodeToString(new byte[33]);

        for (String publicKey : List.of(unpadded, shortKey, longKey)) {
            mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType("application/json")
                            .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\"}".formatted(publicKey)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        mockMvc.perform(post("/api/v1/me/signing-keys").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\", \"deviceName\": \"%s\"}"
                                .formatted(ed25519PublicKey(), "a".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 외부망에서는_서명_공개키를_제출할_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(post("/api/v1/me/signing-keys").with(external()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType("application/json")
                        .content("{\"algorithm\": \"Ed25519\", \"publicKey\": \"%s\"}".formatted(ed25519PublicKey())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 내부망에서는_웹쉘_공개키와_지문을_받는다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(get("/api/v1/webshell/public-key").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encryption.algorithm").value("X25519"))
                .andExpect(jsonPath("$.encryption.fingerprint").value(org.hamcrest.Matchers.startsWith("sha256:")))
                .andExpect(jsonPath("$.signing.algorithm").value("Ed25519"))
                .andExpect(jsonPath("$.signing.publicKey").isNotEmpty());
    }

    @Test
    void 없는_경로와_지원하지_않는_메서드도_명세_오류_형식으로_응답한다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        String token = json(loginInternal(mockMvc, "EMP001"), "$.accessToken");

        mockMvc.perform(get("/api/v1/me/unknown").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/me").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
