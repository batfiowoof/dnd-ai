package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.TurnMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Session-creation payload. Beyond the character + world setting, the host configures
 * how turns are handled and how the DM behaves. All settings are optional with sensible
 * defaults applied server-side (see {@code GameSessionService.createSession}).
 */
public record CreateSessionRequest(
        @NotBlank(message = "Player name is required") String playerName,
        @NotNull(message = "Character ID is required") UUID characterId,
        String worldSetting,
        /**
         * When set, the session is compiled from a saved {@link com.dungeon.master.model.entity.World}
         * owned by the host: its rendered setting, milestones, and custom monsters take precedence over
         * the free-text {@code worldSetting}/{@code milestones} fields above.
         */
        UUID worldId,
        TurnMode turnMode,
        Integer maxPlayers,
        Difficulty difficulty,
        DmStyle dmStyle,
        DmLength dmLength,
        Boolean allowAiCombat,
        Boolean allowAiRolls,
        Boolean allowAiDisposition,
        Integer collabWindowSeconds,
        List<Milestone> milestones,
        /**
         * When set, this session explicitly continues a finished session: its end-of-session recap is
         * injected into the opening and seeded into RAG so the story carries over.
         */
        UUID continuedFromSessionId
) {
}
