package site.siyoung.beforeaibe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Project;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // 기획서 새 버전을 쌓을 때 같은 프로젝트의 동시 요청이 같은 version 번호를 받지 않도록 행을 잠금(SELECT ... FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);

    List<Project> findAllByOrderByCreatedAtDescIdDesc(Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select p from Project p
            where p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)
            order by p.createdAt desc, p.id desc
            """)
    List<Project> findPageAfter(@Param("createdAt") Instant createdAt, @Param("id") UUID id, Limit limit);
}
