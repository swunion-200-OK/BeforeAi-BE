package site.siyoung.beforeaibe.support;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 정책이 아직 한 건도 없어도 같은 행을 잠가 전체 정책 변경·배포 순서를 보장함.
@Component
@RequiredArgsConstructor
public class PolicyChangeLock {
    private final JdbcTemplate jdbcTemplate;
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbcTemplate.queryForObject("select id from policy_change_lock where id = 1 for update", Integer.class);
    }
}
