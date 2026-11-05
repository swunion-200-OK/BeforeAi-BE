package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Rejection;

import java.util.Optional;
import java.util.UUID;

public interface RejectionRepository extends JpaRepository<Rejection, UUID> {

    Optional<Rejection> findFirstByRequest_IdOrderByCreatedAtDesc(UUID requestId);
}
