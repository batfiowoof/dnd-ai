package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.PlayerStateEvent;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.QuestService;
import com.dungeon.master.service.game.QuestService.QuestResult;
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
 * Engine-owned quest tools the AI DM calls during a narrative turn to drive authored quests. The DM may
 * ONLY name quest (and objective) keys from the session's 'Quests' directive — the engine validates every
 * key, mutates the state, pays rewards on completion, and broadcasts the refreshed party state plus a
 * system log line. Follows the {@code DmCampaignTools.awardMilestone} pattern; per-turn session context
 * arrives via {@link ToolContext} (see {@code DmRollTools}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmQuestTools {

    private final QuestService questService;
    private final PlayerRepository playerRepository;
    private final PlayerStateService playerStateService;
    private final SimpMessagingTemplate messagingTemplate;

    @Tool(description = "Mark a quest as taken up by the party, moving it from available to active. Pass the "
            + "exact quest key from the 'Quests' directive — never invent one. Call this when the party "
            + "clearly commits to a quest, then narrate. Rejected if the quest is locked or already active.")
    public QuestResult startQuest(
            @ToolParam(description = "Exact quest key from the Quests directive, verbatim") String questKey,
            ToolContext toolContext) {
        UUID sessionId = sessionId(toolContext);
        QuestResult result = questService.startQuest(sessionId, questKey);
        broadcast(sessionId, result, "Quest started: " + safe(result.title()), false);
        log.info("Tool startQuest: session={}, key={}, changed={}", sessionId, questKey, result.changed());
        return result;
    }

    @Tool(description = "Tick off one objective of an ACTIVE quest when the party genuinely completes that "
            + "step. Pass the exact quest key and the exact objective key from the 'Quests' directive. Call "
            + "this as each objective is achieved, then narrate the progress.")
    public QuestResult advanceQuest(
            @ToolParam(description = "Exact quest key from the Quests directive, verbatim") String questKey,
            @ToolParam(description = "Exact objective key of the completed step, verbatim") String objectiveKey,
            ToolContext toolContext) {
        UUID sessionId = sessionId(toolContext);
        QuestResult result = questService.advanceQuest(sessionId, questKey, objectiveKey);
        broadcast(sessionId, result, "Quest updated: " + safe(result.title()) + " — " + safe(result.note()), false);
        log.info("Tool advanceQuest: session={}, quest={}, objective={}, changed={}",
                sessionId, questKey, objectiveKey, result.changed());
        return result;
    }

    @Tool(description = "Complete a quest when the party has genuinely finished it. The engine pays the "
            + "authored rewards (loot and coin to the party's inventory, and — if the quest links a "
            + "milestone — advances the whole party a level), applies any NPC disposition changes, and "
            + "unlocks quests that depended on this one. Pass the exact quest key from the 'Quests' "
            + "directive; call this BEFORE narrating the payoff, and if it is rejected do not claim the reward.")
    public QuestResult completeQuest(
            @ToolParam(description = "Exact quest key from the Quests directive, verbatim") String questKey,
            ToolContext toolContext) {
        UUID sessionId = sessionId(toolContext);
        QuestResult result = questService.completeQuest(sessionId, questKey);
        String text = "Quest complete: " + safe(result.title())
                + (result.effects().isEmpty() ? "" : " — " + String.join("; ", result.effects()));
        broadcast(sessionId, result, text, true);
        log.info("Tool completeQuest: session={}, key={}, changed={}", sessionId, questKey, result.changed());
        return result;
    }

    @Tool(description = "Fail a quest when the party has irrecoverably blown it (a key NPC died, a deadline "
            + "passed, they betrayed the giver). The engine marks it failed and blocks any quests that "
            + "required it. Pass the exact quest key from the 'Quests' directive and a brief reason, then "
            + "narrate the consequence.")
    public QuestResult failQuest(
            @ToolParam(description = "Exact quest key from the Quests directive, verbatim") String questKey,
            @ToolParam(description = "Brief reason the quest failed, for the log") String reason,
            ToolContext toolContext) {
        UUID sessionId = sessionId(toolContext);
        QuestResult result = questService.failQuest(sessionId, questKey, reason);
        String text = "Quest failed: " + safe(result.title())
                + (result.effects().isEmpty() ? "" : " — " + String.join("; ", result.effects()));
        broadcast(sessionId, result, text, false);
        log.info("Tool failQuest: session={}, key={}, changed={}, reason={}",
                sessionId, questKey, result.changed(), reason);
        return result;
    }

    /* ── internals ───────────────────────────────────────────────── */

    private static UUID sessionId(ToolContext toolContext) {
        return (UUID) toolContext.getContext().get(DmRollTools.K_SESSION);
    }

    /**
     * On a state-changing result, refresh each party member's runtime state (rewards may have changed
     * inventory or level — {@code refreshPlayers}) and push a QUEST system line for the game log. Mirrors
     * the {@code awardMilestone} broadcast shape. Best-effort per player.
     */
    private void broadcast(UUID sessionId, QuestResult result, String text, boolean refreshPlayers) {
        if (!result.changed()) {
            return;
        }
        if (refreshPlayers) {
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
        }
        messagingTemplate.convertAndSend("/topic/game/" + sessionId, (Object) Map.of(
                "type", "QUEST",
                "sessionId", sessionId.toString(),
                "title", safe(result.title()),
                "text", text));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
