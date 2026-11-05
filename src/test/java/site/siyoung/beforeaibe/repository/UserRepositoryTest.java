package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import org.springframework.dao.DataIntegrityViolationException;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 사번으로_사용자를_조회한다() {
        Department department = em.persist(RepositoryTestSupport.department("개발팀"));
        User saved = userRepository.saveAndFlush(RepositoryTestSupport.user("EMP001", department));
        em.clear();

        User found = userRepository.findByEmployeeNumber("EMP001").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo("홍길동");
        assertThat(found.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(found.getDepartmentId()).isEqualTo(department.getId());
        assertThat(found.isActive()).isTrue();
        assertThat(found.isPasswordChangeRequired()).isTrue();
        assertThat(found.getOtpSecret()).isNull();
        assertThat(found.isOtpEnabled()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 비밀번호를_바꾸면_변경_필요_표시가_꺼진다() {
        User saved = userRepository.saveAndFlush(RepositoryTestSupport.user("EMP001"));
        saved.changePassword("{bcrypt}new");
        saved.issueOtpSecret("SECRET");
        saved.enableOtp();
        userRepository.flush();
        em.clear();

        User found = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getPasswordHash()).isEqualTo("{bcrypt}new");
        assertThat(found.isPasswordChangeRequired()).isFalse();
        assertThat(found.getOtpSecret()).isEqualTo("SECRET");
        assertThat(found.isOtpEnabled()).isTrue();
    }

    @Test
    void 사번이_겹치면_저장되지_않는다() {
        userRepository.saveAndFlush(RepositoryTestSupport.user("EMP001"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(RepositoryTestSupport.user("EMP001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_부서를_가리키면_저장되지_않는다() {
        User user = User.builder()
                .employeeNumber("EMP001")
                .passwordHash("{bcrypt}hash")
                .name("홍길동")
                .role(Role.EMPLOYEE)
                .departmentId(java.util.UUID.randomUUID())
                .active(true)
                .build();

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
