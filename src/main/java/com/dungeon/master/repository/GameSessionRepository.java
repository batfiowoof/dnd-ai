package com.dungeon.master.repository;

import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.enums.GameStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, UUID> {

    Optional<GameSession> findByCode(String code);

    /**
     * The most recent finished playthrough of a saved World for a given host — the source of
     * campaign-level recap when a new session is started from that World.
     */
    Optional<GameSession> findTopByWorldIdAndStatusAndCreatedByOrderByCreatedAtDesc(
            UUID worldId, GameStatus status, String createdBy);
}
