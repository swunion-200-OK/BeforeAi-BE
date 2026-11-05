package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.DocumentVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    Optional<DocumentVersion> findByDocument_IdAndVersion(UUID documentId, int version);

    List<DocumentVersion> findByDocument_IdOrderByVersionDesc(UUID documentId, Limit limit);

    // 커서(마지막으로 받은 version) 다음 페이지
    List<DocumentVersion> findByDocument_IdAndVersionLessThanOrderByVersionDesc(UUID documentId, int version,
                                                                                 Limit limit);

    // 이 요청이 처음 만든 버전. 그 직전 버전이 작업 문서 diff의 기준이 됨
    Optional<DocumentVersion> findFirstByDocument_IdAndRequest_IdOrderByVersionAsc(UUID documentId, UUID requestId);

    // 주어진 버전 바로 앞의 버전 (번호가 비어 있어도 찾을 수 있도록 LessThan으로 조회)
    Optional<DocumentVersion> findFirstByDocument_IdAndVersionLessThanOrderByVersionDesc(UUID documentId, int version);
}
