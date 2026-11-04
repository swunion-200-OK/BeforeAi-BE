package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Approval;

import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
}
