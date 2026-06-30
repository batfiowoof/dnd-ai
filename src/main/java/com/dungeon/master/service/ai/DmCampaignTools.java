package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CampaignMilestoneService;
import com.dungeon.master.service.game.CampaignMilestoneService.MilestoneResult;
import com.dungeon.master.service.game.PlayerStateService;
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
 * Engine-owned campaign tool the AI DM calls during a narrative turn. {@code awardMilestone} is the
 * ONLY way the DM can level the party, and it is constrained to the campaign's authored milestones:
 * the engine validates the key, advances the whole party once, and broadcasts the refreshed state.
 * Per-turn session context is passed via Spring AI's {@link ToolContext} (see {@code DmRollTools}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmCampaignTools {

    private final CampaignMilestoneService milestoneService;
    private final PlayerRepository playerRepository;
    private final PlayerStateService playerStateService;
    private final SimpMessagingTemplate messagingTemplate;

    @Tool(description = "Award an AUTHORED campaign milestone when the party has genuinely achieved it "
            + "(defeated the named foe, reached the key location, completed the objective). The engine "
            + "advances the WHOLE party one level and returns the result. You may ONLY pass a milestone "
            + "key listed in the session's 'Campaign milestones' directive — never invent one. Each "
            + "milestone awards once; a repeat call is rejected. Call this BEFORE narrating the level-up, "
            + "and if it is rejected, do not claim the party levelled up.")
    public MilestoneResult awardMilestone(
            @ToolParam(description = "Exact milestone key from the Campaign milestones list, verbatim") String key,
            ToolContext toolContext) {
        Map<String, Object> ctx = toolContext.getContext();
        UUID sessionId = (UUID) ctx.get(DmRollTools.K_SESSION);

        MilestoneResult result = milestoneService.completeMilestone(sessionId, key);
        if (result.awarded()) {
            // Push each party member's refreshed runtime state, then a system line for the game log.
            for (Player p : playerRepository.findBySessionId(sessionId)) {
                if (p.getRole() != PlayerRole.PLAYER) {
                    continue;
                }
                try {
                    messagingTemplate.convertAndSend("/topic/game/" + sessionId,
                            PlayerStateEvent.of(sessionId, playerStateService.getState(p.getId())));
                } catch (Exception ignored) {
                    // A player without seeded runtime state simply has nothing live to refresh.
                }
            }
            messagingTemplate.convertAndSend("/topic/game/" + sessionId, (Object) Map.of(
                    "type", "MILESTONE",
                    "sessionId", sessionId.toString(),
                    "title", result.title() == null ? "" : result.title(),
                    "text", "Milestone reached: " + result.title() + " — the party advances a level!"));
        }
        log.info("Tool awardMilestone: session={}, key={}, awarded={}", sessionId, key, result.awarded());
        return result;
    }
}
