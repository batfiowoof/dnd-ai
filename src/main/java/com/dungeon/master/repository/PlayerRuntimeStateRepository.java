package com.dungeon.master.repository;

import com.dungeon.master.model.entity.PlayerRuntimeState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlayerRuntimeStateRepository extends JpaRepository<PlayerRuntimeState, UUID> {
    List<PlayerRuntimeState> findBySessionId(UUID sessionId);
}
