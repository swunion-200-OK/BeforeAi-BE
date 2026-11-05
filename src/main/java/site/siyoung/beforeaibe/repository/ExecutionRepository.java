package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {

    // 킬 스위치 대상: 요청의 가장 최근 실행 세션 중 해당 상태인 것
    Optional<Execution> findFirstByRequest_IdAndStatusOrderByCreatedAtDesc(UUID requestId, ExecutionStatus status);
}
