package site.siyoung.beforeaibe.controller;

import com.jayway.jsonpath.JsonPath;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import jakarta.servlet.http.Cookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class ApiTestSupport {

    static final String PASSWORD = "init-password-1!";
    static final String INTERNAL_IP = "100.64.0.10";
    static final String EXTERNAL_IP = "203.0.113.10";

    private ApiTestSupport() {
    }

    static RequestPostProcessor internal() {
        return remoteAddr(INTERNAL_IP);
    }

    static RequestPostProcessor external() {
        return remoteAddr(EXTERNAL_IP);
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    static User saveUser(UserRepository userRepository, PasswordEncoder passwordEncoder,
                         String employeeNumber, boolean passwordChangeRequired) {
        return userRepository.saveAndFlush(User.builder()
                .employeeNumber(employeeNumber)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .name("홍길동")
                .role(Role.EMPLOYEE)
                .active(true)
                .passwordChangeRequired(passwordChangeRequired)
                .build());
    }

    // 역할·부서가 필요한 API(프로젝트·기획서 권한 등) 테스트용 사용자. 비밀번호 변경은 끝난 상태
    static User saveUser(UserRepository userRepository, PasswordEncoder passwordEncoder,
                         String employeeNumber, Role role, UUID departmentId) {
        return userRepository.saveAndFlush(User.builder()
                .employeeNumber(employeeNumber)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .name("홍길동")
                .role(role)
                .departmentId(departmentId)
                .active(true)
                .passwordChangeRequired(false)
                .build());
    }

    static String loginBody(String employeeNumber, String password) {
        return """
                {"employeeNumber": "%s", "password": "%s"}
                """.formatted(employeeNumber, password);
    }

    // 내부망에서 로그인하고 액세스 토큰을 꺼냄
    static MvcResult loginInternal(MockMvc mockMvc, String employeeNumber) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(internal())
                        .contentType("application/json")
                        .content(loginBody(employeeNumber, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    static String json(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    static Cookie refreshCookie(MvcResult result) {
        return result.getResponse().getCookie(AuthController.REFRESH_TOKEN_COOKIE);
    }

    static String otpCode(String secret) throws Exception {
        return new DefaultCodeGenerator().generate(secret, Instant.now().getEpochSecond() / 30);
    }

    // 기기에서 만든 것과 같은 형식의 Ed25519 raw 공개키(Base64)
    static String ed25519PublicKey() throws Exception {
        byte[] encoded = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        return Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }
}
