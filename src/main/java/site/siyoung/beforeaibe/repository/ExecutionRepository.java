package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Execution;
import site.siyoung.beforeaibe.entity.enums.ExecutionStatus;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {

    // 킬 스위치 대상: 요청의 가장 최근 실행 세션 중 해당 상태인 것
    Optional<Execution> findFirstByRequest_IdAndStatusOrderByCreatedAtDesc(UUID requestId, ExecutionStatus status);

    Optional<Execution> findFirstByRequest_IdAndKilledAtNotNullOrderByKilledAtDesc(UUID requestId);

    // 웹쉘 콜백·전사 중단 응답은 웹쉘이 발급한 executionId로 실행 세션을 가리킴
    Optional<Execution> findByWebshellExecutionId(UUID webshellExecutionId);

    // 요청 행을 먼저 잠그기 위해 실행 세션을 영속성 컨텍스트에 올리지 않고 요청 ID만 읽음.
    // 잠금 뒤에 실행 세션을 읽어야 다른 트랜잭션(전사 중단 등)이 바꾼 최신 상태를 봄
    @Query("select e.request.id from Execution e where e.webshellExecutionId = :webshellExecutionId")
    Optional<UUID> findRequestIdByWebshellExecutionId(@Param("webshellExecutionId") UUID webshellExecutionId);
}
