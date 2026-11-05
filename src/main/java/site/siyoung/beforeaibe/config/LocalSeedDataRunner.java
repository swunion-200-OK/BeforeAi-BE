package site.siyoung.beforeaibe.config;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.SpecVersion;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.repository.UserRepository;

/**
 * 로컬 개발용 시드 데이터. local 프로필에서만 동작하고, 관리자 계정이 이미 있으면 아무것도 하지 않음.
 * 계정 생성 API(3.2)가 생기기 전까지 로그인·승인 흐름을 수동으로 확인하는 데 씀.
 * 모든 계정의 비밀번호는 SEED_PASSWORD 환경변수 값.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalSeedDataRunner implements ApplicationRunner {

    private static final String ADMIN_EMPLOYEE_NUMBER = "ADMIN001";

    private final EntityManager em;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.password}")
    private String seedPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.findByEmployeeNumber(ADMIN_EMPLOYEE_NUMBER).isPresent()) {
            return;
        }

        Department dev = persist(Department.builder().name("개발팀").build());
        Department ops = persist(Department.builder().name("운영팀").build());

        persist(user(ADMIN_EMPLOYEE_NUMBER, "관리자", Role.ADMIN, dev));
        User manager = persist(user("MGR001", "김팀장", Role.DEPT_MANAGER, dev));
        User owner = persist(user("EMP002", "김담당", Role.EMPLOYEE, dev));
        persist(user("EMP001", "홍길동", Role.EMPLOYEE, dev));
        persist(user("EMP101", "이운영", Role.EMPLOYEE, ops));

        // 담당자는 2명 이상 (기능 명세서 5.2)
        Project project = Project.builder()
                .name("인증 서비스")
                .department(dev)
                .repository("git@internal:auth-service.git")
                .build();
        project.addOwner(owner);
        project.addOwner(manager);
        persist(project);

        persist(SpecVersion.builder()
                .project(project)
                .version(1)
                .body("# 인증 서비스 기획서\n로그인 실패 시 안내 메시지를 표시한다.")
                .createdBy(manager)
                .build());

        log.info("로컬 시드 데이터를 만들었습니다: 계정 5개(ADMIN001, MGR001, EMP001, EMP002, EMP101), 부서 2개, 프로젝트 1개");
    }

    private User user(String employeeNumber, String name, Role role, Department department) {
        return User.builder()
                .employeeNumber(employeeNumber)
                .passwordHash(passwordEncoder.encode(seedPassword))
                .name(name)
                .role(role)
                .departmentId(department.getId())
                .active(true)
                .passwordChangeRequired(false)
                .build();
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }
}
