package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.TurnMode;

import java.util.List;
import java.util.UUID;

public record GameStateDto(
        UUID sessionId,
        String joinCode,
        GameStatus status,
        List<PlayerDto> players,
        UUID currentTurnPlayerId,
        int turnNumber,
        String createdBy,
        String worldSetting,
        TurnMode turnMode,
        int maxPlayers,
        Difficulty difficulty,
        DmStyle dmStyle,
        DmLength dmLength,
        boolean allowAiCombat,
        boolean allowAiRolls,
        int collabWindowSeconds,
        List<PendingCheckDto> pendingChecks
) {
}
