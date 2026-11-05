package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.dto.auth.LoginRequest;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 트랜잭션·행 잠금이 필요해 @Transactional 없이 실제 DB에 커밋하며 검증
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthServiceConcurrencyTest {

    private static final int THREADS = 5;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void 같은_리프레시_토큰으로_동시에_재발급하면_하나만_성공한다() throws Exception {
        String employeeNumber = "CON-" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.saveAndFlush(User.builder()
                .employeeNumber(employeeNumber)
                .passwordHash(passwordEncoder.encode("password"))
                .name("동시성")
                .role(Role.EMPLOYEE)
                .active(true)
                .build());
        String refreshToken = authService.login(new LoginRequest(employeeNumber, "password"), Network.INTERNAL)
                .refreshToken();

        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> {
                start.await();
                try {
                    authService.refresh(refreshToken);
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Boolean>> futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            long succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
