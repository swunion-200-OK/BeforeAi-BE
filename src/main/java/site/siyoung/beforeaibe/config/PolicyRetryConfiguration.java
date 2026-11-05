package site.siyoung.beforeaibe.config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import site.siyoung.beforeaibe.service.PolicyDeploymentService;

// 저장된 최신 서명 정책만 재전송한다. 오래된 정책을 다시 보내 정책을 되돌리지 않음.
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "webshell.policy-retry-enabled", havingValue = "true", matchIfMissing = true)
public class PolicyRetryConfiguration {
    private final PolicyDeploymentService deployments;
    @Scheduled(fixedDelayString = "${webshell.policy-retry-delay:30000}", initialDelayString = "${webshell.policy-retry-delay:30000}")
    public void retry() {
        try {
            var pending = deployments.pendingVersion();
            if (pending != null) deployments.deploy(pending);
        } catch (RuntimeException e) {
            log.warn("웹쉘 정책 재배포 실패: {}", e.getClass().getSimpleName());
        }
    }
}
