package com.dungeon.master.repository;

import com.dungeon.master.model.entity.TurnEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TurnEventRepository extends JpaRepository<TurnEvent, UUID> {

    List<TurnEvent> findBySessionIdOrderByTurnNumberDesc(UUID sessionId);

    List<TurnEvent> findTop20BySessionIdOrderByTurnNumberDesc(UUID sessionId);

    List<TurnEvent> findTop5BySessionIdOrderByTurnNumberDesc(UUID sessionId);

    Optional<TurnEvent> findTopBySessionIdOrderByTurnNumberDesc(UUID sessionId);
}
