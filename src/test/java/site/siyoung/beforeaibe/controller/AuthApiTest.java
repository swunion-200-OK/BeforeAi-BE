package site.siyoung.beforeaibe.controller;

import jakarta.servlet.http.Cookie;
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
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.OtpService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.PASSWORD;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginBody;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.otpCode;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.refreshCookie;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OtpService otpService;

    @Test
    void 내부망_로그인은_액세스_토큰과_HttpOnly_리프레시_쿠키를_발급한다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", true);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(internal())
                        .contentType("application/json")
                        .content(loginBody("EMP001", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Network", "INTERNAL"))
                .andExpect(jsonPath("$.otpRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.temporaryToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        Cookie cookie = refreshCookie(result);
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
    }

    @Test
    void 비밀번호가_틀리면_401_UNAUTHENTICATED를_돌려준다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);

        mockMvc.perform(post("/api/v1/auth/login").with(internal())
                        .contentType("application/json")
                        .content(loginBody("EMP001", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.details").isMap());
    }

    @Test
    void 필수_값이_없으면_400_VALIDATION_ERROR를_돌려준다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").with(internal())
                        .contentType("application/json")
                        .content("{\"employeeNumber\": \"EMP001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields.password").exists());
    }

    @Test
    void 외부망_로그인은_OTP를_등록하지_않았으면_거부된다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);

        mockMvc.perform(post("/api/v1/auth/login").with(external())
                        .contentType("application/json")
                        .content(loginBody("EMP001", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Network", "EXTERNAL"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 외부망_로그인은_임시_토큰을_주고_OTP를_확인한_뒤에야_토큰을_발급한다() throws Exception {
        User user = saveUser(userRepository, passwordEncoder, "EMP001", false);
        String secret = otpService.generateSecret();
        user.issueOtpSecret(secret);
        user.enableOtp();
        userRepository.saveAndFlush(user);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(external())
                        .contentType("application/json")
                        .content(loginBody("EMP001", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        String temporaryToken = json(login, "$.temporaryToken");
        assertThat(login.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        // 임시 토큰으로는 일반 API를 쓸 수 없음
        mockMvc.perform(get("/api/v1/me").with(external()).header(HttpHeaders.AUTHORIZATION, bearer(temporaryToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OTP_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/otp/verify").with(external())
                        .contentType("application/json")
                        .content("""
                                {"temporaryToken": "%s", "otpCode": "000000x"}
                                """.formatted(temporaryToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/otp/verify").with(external())
                        .contentType("application/json")
                        .content("""
                                {"temporaryToken": "%s", "otpCode": "%s"}
                                """.formatted(temporaryToken, otpCode(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        assertThat(refreshCookie(verified).getValue()).isNotBlank();

        mockMvc.perform(get("/api/v1/me").with(external())
                        .header(HttpHeaders.AUTHORIZATION, bearer(json(verified, "$.accessToken"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("EXTERNAL"));
    }

    @Test
    void 리프레시_토큰을_쓰면_새_토큰을_주고_쓴_토큰은_다시_쓸_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        Cookie original = refreshCookie(loginInternal(mockMvc, "EMP001"));

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").with(internal()).cookie(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        assertThat(refreshCookie(refreshed).getValue()).isNotEqualTo(original.getValue());

        mockMvc.perform(post("/api/v1/auth/refresh").with(internal()).cookie(original))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 리프레시_쿠키가_없으면_재발급되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").with(internal()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 로그아웃하면_쿠키를_지우고_그_리프레시_토큰은_쓸_수_없다() throws Exception {
        saveUser(userRepository, passwordEncoder, "EMP001", false);
        MvcResult login = loginInternal(mockMvc, "EMP001");
        Cookie cookie = refreshCookie(login);

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, bearer(json(login, "$.accessToken")))
                        .cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(refreshCookie(logout).getMaxAge()).isZero();

        mockMvc.perform(post("/api/v1/auth/refresh").with(internal()).cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰_없이_보호된_API를_부르면_401과_X_Network_헤더를_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(external()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Network", "EXTERNAL"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/me").with(internal()).header(HttpHeaders.AUTHORIZATION, bearer("broken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 허용한_프론트_출처는_쿠키를_포함한_CORS_요청과_X_Network_헤더_읽기가_허용된다() throws Exception {
        mockMvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        saveUser(userRepository, passwordEncoder, "EMP001", false);
        mockMvc.perform(post("/api/v1/auth/login").with(internal())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType("application/json")
                        .content(loginBody("EMP001", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString("X-Network")));
    }

    @Test
    void 허용하지_않은_출처의_CORS_요청은_거부된다() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
