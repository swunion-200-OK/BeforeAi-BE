package site.siyoung.beforeaibe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import site.siyoung.beforeaibe.entity.RequestStatusEvent;

import java.util.List;
import java.util.UUID;

public interface RequestStatusEventRepository extends JpaRepository<RequestStatusEvent, UUID> {

    List<RequestStatusEvent> findByRequest_IdOrderByOccurredAtAsc(UUID requestId);
}
