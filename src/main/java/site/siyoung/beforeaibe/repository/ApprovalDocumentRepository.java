package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.ApprovalDocument;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalDocumentRepository extends JpaRepository<ApprovalDocument, UUID> {

    Optional<ApprovalDocument> findByRequest_Id(UUID requestId);
}
