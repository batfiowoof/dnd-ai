package com.dungeon.master.repository;

import com.dungeon.master.model.entity.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, UUID> {

    Optional<GameSession> findByCode(String code);
}
