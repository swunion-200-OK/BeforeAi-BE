package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.Document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    // DOC 작업은 target(문서 경로)으로 문서를 가리킴. (project_id, path)는 unique
    Optional<Document> findByProject_IdAndPath(UUID projectId, String path);

    List<Document> findByProject_IdOrderByCreatedAtDescIdDesc(UUID projectId, Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select d from Document d
            where d.project.id = :projectId
              and (d.createdAt < :createdAt or (d.createdAt = :createdAt and d.id < :id))
            order by d.createdAt desc, d.id desc
            """)
    List<Document> findPageAfter(@Param("projectId") UUID projectId, @Param("createdAt") Instant createdAt,
                                 @Param("id") UUID id, Limit limit);
}
