package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.PolicyVersion;

import java.util.Optional;
import java.util.UUID;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {

    // 가장 큰 version이 현재 정책
    Optional<PolicyVersion> findTopByOrderByVersionDesc();
}
