package com.dungeon.master.repository;

import com.dungeon.master.model.entity.World;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorldRepository extends JpaRepository<World, UUID> {

    List<World> findByOwnerUsernameOrderByUpdatedAtDesc(String ownerUsername);

    Optional<World> findByIdAndOwnerUsername(UUID id, String ownerUsername);
}
