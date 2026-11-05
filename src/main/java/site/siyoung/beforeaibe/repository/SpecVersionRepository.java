package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.SpecVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpecVersionRepository extends JpaRepository<SpecVersion, UUID> {

    // 가장 큰 version이 현재 기획서
    Optional<SpecVersion> findTopByProjectIdOrderByVersionDesc(UUID projectId);

    Optional<SpecVersion> findByProjectIdAndVersion(UUID projectId, int version);

    List<SpecVersion> findByProjectIdOrderByVersionDesc(UUID projectId, Limit limit);

    // 커서(마지막으로 받은 version) 다음 페이지
    List<SpecVersion> findByProjectIdAndVersionLessThanOrderByVersionDesc(UUID projectId, int version, Limit limit);
}
