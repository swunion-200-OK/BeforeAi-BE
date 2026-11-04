package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.Request;

import java.util.UUID;

public interface RequestRepository extends JpaRepository<Request, UUID> {
}
