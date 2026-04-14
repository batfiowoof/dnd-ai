package com.dungeon.master.repository;

import com.dungeon.master.model.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CharacterRepository extends JpaRepository<Character, UUID> {

    List<Character> findByOwnerUsernameOrderByUpdatedAtDesc(String ownerUsername);

    Optional<Character> findByIdAndOwnerUsername(UUID id, String ownerUsername);
}
