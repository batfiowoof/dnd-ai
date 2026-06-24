package com.dungeon.master.repository;

import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.enums.CombatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CombatEncounterRepository extends JpaRepository<CombatEncounter, UUID> {
    Optional<CombatEncounter> findBySessionIdAndStatus(UUID sessionId, CombatStatus status);
}
