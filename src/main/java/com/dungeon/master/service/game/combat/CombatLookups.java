package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.PlayerStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Small shared read helpers that several combat collaborators need (a player's display name and
 * their structured conditions). Lives in one place so the leaf logic is not duplicated across
 * the orchestrator and the extracted services.
 */
@Component
@RequiredArgsConstructor
public class CombatLookups {

    private final PlayerRepository playerRepository;
    private final PlayerStateService playerStateService;

    public String playerName(UUID playerId) {
        return playerRepository.findById(playerId).map(Player::getCharacterName).orElse("an ally");
    }

    /** A player's structured conditions (empty when none / no runtime state). */
    public List<ActiveCondition> playerConds(UUID playerId) {
        try {
            return playerStateService.conditions(playerId);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
