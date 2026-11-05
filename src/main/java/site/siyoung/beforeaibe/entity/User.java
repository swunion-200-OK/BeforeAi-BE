package site.siyoung.beforeaibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import site.siyoung.beforeaibe.entity.enums.Role;

import java.util.UUID;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String employeeNumber;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // departments FK가 있지만 엔티티는 기존처럼 ID로만 참조 (관리자 등 부서가 없을 수 있음)
    private UUID departmentId;

    @Column(nullable = false)
    private boolean active;

    // 관리자가 발급한 초기 비밀번호는 최초 로그인 때 내부망에서 바꿔야 함 (ADM-01)
    @Column(nullable = false)
    private boolean passwordChangeRequired;

    // TOTP 비밀키. 발급만 하고 확인 전이면 otpEnabled = false
    @Column(length = 64)
    private String otpSecret;

    @Column(nullable = false)
    private boolean otpEnabled;

    @Builder
    private User(String employeeNumber, String passwordHash, String name, Role role, UUID departmentId,
                 boolean active, boolean passwordChangeRequired) {
        this.employeeNumber = employeeNumber;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.departmentId = departmentId;
        this.active = active;
        this.passwordChangeRequired = passwordChangeRequired;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = false;
    }

    // 확인 전에는 다시 발급할 수 있도록 덮어씀. 등록 완료 후 재등록 차단은 서비스에서 함
    public void issueOtpSecret(String otpSecret) {
        this.otpSecret = otpSecret;
    }

    public void enableOtp() {
        this.otpEnabled = true;
    }
}
