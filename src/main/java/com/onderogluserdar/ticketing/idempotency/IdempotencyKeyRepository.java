package com.onderogluserdar.ticketing.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByUserIdAndEndpointAndIdempotencyKey(
            UUID userId, String endpoint, String idempotencyKey);

    @Transactional
    @Modifying
    @Query("delete from IdempotencyKey k where k.id = :id and k.expiresAt <= :now")
    int deleteIfExpired(@Param("id") UUID id, @Param("now") Instant now);
}
