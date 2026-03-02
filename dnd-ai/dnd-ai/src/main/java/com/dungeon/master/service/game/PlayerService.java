package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.model.dto.CharacterCreateRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerService {

    private final PlayerRepository playerRepository;

    @Transactional
    public PlayerDto updateCharacter(CharacterCreateRequest request, String username) {
        Player player = playerRepository.findBySessionIdAndUsername(request.sessionId(), username)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "Player not found: " + username + " in session " + request.sessionId()));

        player.setCharacterName(request.characterName());
        if (request.characterSheet() != null) {
            player.setCharacterSheet(request.characterSheet());
        }
        player = playerRepository.save(player);

        log.info("Character updated: player={}, character={}", username, request.characterName());
        return toPlayerDto(player);
    }

    public PlayerDto getPlayer(UUID playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));
        return toPlayerDto(player);
    }

    public Player getPlayerEntity(UUID playerId) {
        return playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));
    }

    private PlayerDto toPlayerDto(Player player) {
        return new PlayerDto(
                player.getId(),
                player.getUsername(),
                player.getCharacterName(),
                player.getRole(),
                player.getTurnIndex());
    }
}
