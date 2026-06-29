package com.dungeon.master.model.mapper;

import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps {@link Player} entities to {@link PlayerDto}. Single source of truth for this mapping, which
 * was previously duplicated byte-for-byte in {@code PlayerService} and {@code GameSessionService} —
 * including the character-portrait lookup. Kept as a plain Spring component (no MapStruct) to stay
 * Lombok-friendly and dependency-light.
 */
@Component
@RequiredArgsConstructor
public class PlayerMapper {

    private final CharacterRepository characterRepository;

    public PlayerDto toDto(Player player) {
        String imageUrl = player.getCharacterId() == null ? null
                : characterRepository.findById(player.getCharacterId())
                        .map(Character::getImageUrl)
                        .orElse(null);
        return new PlayerDto(
                player.getId(),
                player.getUsername(),
                player.getCharacterName(),
                player.getRole(),
                player.getTurnIndex(),
                player.getCharacterId(),
                imageUrl,
                player.getCharacterSheet());
    }
}
