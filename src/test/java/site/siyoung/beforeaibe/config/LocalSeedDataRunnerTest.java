package site.siyoung.beforeaibe.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "seed.password=seed-password-1!")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LocalSeedDataRunnerTest {

    @Autowired
    private LocalSeedDataRunner runner;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void local_프로필에서는_시드_계정을_만들고_다시_실행해도_중복으로_만들지_않는다() {
        User admin = userRepository.findByEmployeeNumber("ADMIN001").orElseThrow();
        long count = userRepository.count();

        runner.run(new DefaultApplicationArguments());

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isPasswordChangeRequired()).isFalse();
        assertThat(passwordEncoder.matches("seed-password-1!", admin.getPasswordHash())).isTrue();
        assertThat(userRepository.findByEmployeeNumber("MGR001")).isPresent();
        assertThat(userRepository.count()).isEqualTo(count);
    }
}
