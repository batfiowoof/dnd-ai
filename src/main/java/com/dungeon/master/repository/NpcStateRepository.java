package com.dungeon.master.repository;

import com.dungeon.master.model.entity.NpcState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-session NPC disposition rows. */
public interface NpcStateRepository extends JpaRepository<NpcState, UUID> {

    List<NpcState> findBySessionId(UUID sessionId);

    Optional<NpcState> findBySessionIdAndNpcNameIgnoreCase(UUID sessionId, String npcName);
}
