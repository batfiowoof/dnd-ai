package com.dungeon.master.repository;

import com.dungeon.master.model.entity.Enemy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnemyRepository extends JpaRepository<Enemy, UUID> {
    List<Enemy> findBySessionId(UUID sessionId);
}
