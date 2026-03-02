package com.dungeon.master.repository;

import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlayerRepository extends JpaRepository<Player, UUID> {

    List<Player> findBySessionId(UUID sessionId);

    Optional<Player> findBySessionIdAndUsername(UUID sessionId, String username);

    Optional<Player> findBySessionIdAndRole(UUID sessionId, PlayerRole role);

    int countBySessionId(UUID sessionId);

    long countBySessionIdAndRole(UUID sessionId, PlayerRole role);
}
