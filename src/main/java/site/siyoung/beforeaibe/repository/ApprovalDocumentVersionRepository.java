package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.ApprovalDocumentVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalDocumentVersionRepository extends JpaRepository<ApprovalDocumentVersion, UUID> {

    Optional<ApprovalDocumentVersion> findByDocument_IdAndVersion(UUID documentId, int version);

    // 판 이력 (최신 판 먼저)
    List<ApprovalDocumentVersion> findByDocument_IdOrderByVersionDesc(UUID documentId);
}
