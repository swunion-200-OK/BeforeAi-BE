package site.siyoung.beforeaibe.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    boolean existsByUserIdAndStatus(UUID userId, SigningKeyStatus status);

    boolean existsByUserIdAndStatusIn(UUID userId, Collection<SigningKeyStatus> statuses);

    boolean existsByPublicKey(String publicKey);

    List<SigningKey> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Limit limit);

    // 커서(마지막으로 받은 항목의 createdAt, id) 다음 페이지
    @Query("""
            select k from SigningKey k
            where k.user.id = :userId
              and (k.createdAt < :createdAt or (k.createdAt = :createdAt and k.id < :id))
            order by k.createdAt desc, k.id desc
            """)
    List<SigningKey> findPageAfter(@Param("userId") UUID userId,
                                   @Param("createdAt") Instant createdAt,
                                   @Param("id") UUID id,
                                   Limit limit);
}
