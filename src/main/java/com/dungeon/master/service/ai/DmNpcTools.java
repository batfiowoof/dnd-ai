package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.NpcStateDto;
import com.dungeon.master.model.dto.NpcStateEvent;
import com.dungeon.master.model.enums.DispositionBand;
import com.dungeon.master.service.game.NpcStateService;
import com.dungeon.master.service.game.NpcStateService.AdjustResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Engine-owned NPC tool the AI DM calls during a narrative turn to change how an NPC feels about the
 * party, based on what the party just did. The engine clamps the score, derives the band, persists it,
 * and broadcasts the refreshed state — the DM then narrates the shifted tone. Follows the
 * {@code DmCampaignTools.awardMilestone} pattern; per-turn session context arrives via {@link ToolContext}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmNpcTools {

    private final NpcStateService npcStateService;
    private final SimpMessagingTemplate messagingTemplate;

    @Tool(description = "Adjust how an NPC feels about the party when their conduct genuinely changes the "
            + "relationship (kept a promise, insulted, saved, betrayed them). Pass the NPC's exact name "
            + "from the 'NPC relationships' directive and a signed delta on a -100..100 scale — a small "
            + "delta (±5 to ±15) for a minor moment, larger (±20 to ±40) for something significant; "
            + "positive warms the attitude, negative sours it. The engine clamps the result and returns "
            + "the new attitude band. Call this BEFORE narrating, then let the NPC's tone and willingness "
            + "reflect the new band. Do not call it for trivial or purely transactional exchanges.")
    public AdjustResult adjustDisposition(
            @ToolParam(description = "Exact NPC name from the NPC relationships list, verbatim") String npcName,
            @ToolParam(description = "Signed change to the attitude score, -100..100 (positive = warmer)")
            int delta,
            @ToolParam(description = "Brief reason for the change, for the log") String reason,
            ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        UUID sessionId = (UUID) ctx.get(DmRollTools.K_SESSION);

        AdjustResult result = npcStateService.adjust(sessionId, npcName, delta);
        if (result.found() && result.changed()) {
            messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                    NpcStateEvent.of(sessionId, new NpcStateDto(result.name(), result.disposition(),
                            DispositionBand.fromScore(result.disposition()).label())));
        }
        log.info("Tool adjustDisposition: session={}, npc={}, delta={}, found={}, band={}, reason={}",
                sessionId, npcName, delta, result.found(), result.band(), reason);
        return result;
    }
}
