package com.dungeon.master.repository;

import com.dungeon.master.model.entity.PendingCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PendingCheckRepository extends JpaRepository<PendingCheck, PendingCheck.Key> {

    Optional<PendingCheck> findBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    List<PendingCheck> findBySessionId(UUID sessionId);

    List<PendingCheck> findBySessionIdAndRoundToken(UUID sessionId, UUID roundToken);

    long countBySessionIdAndRoundToken(UUID sessionId, UUID roundToken);

    void deleteBySessionIdAndPlayerId(UUID sessionId, UUID playerId);
}
