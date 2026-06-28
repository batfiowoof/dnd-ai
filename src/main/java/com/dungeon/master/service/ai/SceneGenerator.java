package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.SceneLayout;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.service.game.GridService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Generates the tactical battle map ({@link GridState}) for a new encounter. When the session
 * permits AI combat it asks the chat model to design a compact battlefield as structured JSON
 * ({@link SceneLayout}), grounded in the world setting and recent events; otherwise — or on ANY
 * failure (LLM disabled, timeout, error, or an invalid/empty result) — it falls back to the
 * deterministic open {@link GridService#defaultArena} so combat ALWAYS gets a usable grid.
 *
 * <p>The structured call overrides the DM-narrator system persona so it doesn't pollute a pure
 * map-design request, and uses a fresh {@link ChatClient} over the shared {@link ChatModel}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SceneGenerator {

    private static final String SYSTEM = "You design compact D&D battle maps. Return ONLY the requested JSON.";

    private final ChatModel chatModel;
    private final GameSessionRepository sessionRepository;
    private final RagService ragService;
    private final GridService gridService;

    /**
     * Build the encounter grid and place every combatant on it. Never throws — a failed or
     * disabled LLM call degrades to the default arena.
     */
    public GridState generateScene(UUID sessionId, List<String> playerRefIds, List<String> enemyRefIds) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.isAllowAiCombat()) {
            return fallback(playerRefIds, enemyRefIds);
        }
        try {
            SceneLayout layout = requestLayout(session, sessionId);
            if (layout == null) {
                log.warn("Scene generation returned no layout for session={}, using default arena", sessionId);
                return fallback(playerRefIds, enemyRefIds);
            }
            GridState grid = gridService.fromSceneLayout(layout);
            gridService.placeTokens(grid, playerRefIds, enemyRefIds);
            log.info("Generated LLM battle scene for session={}: {}x{}, {} terrain cells",
                    sessionId, grid.getWidth(), grid.getHeight(),
                    grid.getTerrain() == null ? 0 : grid.getTerrain().size());
            return grid;
        } catch (Exception e) {
            log.warn("Scene generation failed for session={}, using default arena: {}",
                    sessionId, e.getMessage());
            return fallback(playerRefIds, enemyRefIds);
        }
    }

    /** Deterministic open arena + token placement — the guaranteed fallback. */
    private GridState fallback(List<String> playerRefIds, List<String> enemyRefIds) {
        int players = playerRefIds == null ? 0 : playerRefIds.size();
        int enemies = enemyRefIds == null ? 0 : enemyRefIds.size();
        GridState grid = gridService.defaultArena(players, enemies);
        gridService.placeTokens(grid, playerRefIds, enemyRefIds);
        return grid;
    }

    private SceneLayout requestLayout(GameSession session, UUID sessionId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Design a compact tactical battle map for the upcoming D&D encounter.\n");
        if (session.getWorldSetting() != null && !session.getWorldSetting().isBlank()) {
            prompt.append("World setting: ").append(trim(session.getWorldSetting(), 600)).append("\n");
        }
        String recent = recentContext(sessionId);
        if (!recent.isBlank()) {
            prompt.append("Recent events:\n").append(recent).append("\n");
        }
        prompt.append("""
                Return JSON with: width (10-20), height (8-16), terrain (a SMALL handful of \
                special squares, each with type one of WALL, DIFFICULT, or HAZARD), and optional \
                features (a few labelled points of interest). Keep terrain SPARSE so both sides \
                can move freely and reach each other — never wall off the map. Leave the leftmost \
                and rightmost columns open (combatants deploy there). Coordinates are 0-based and \
                must fall inside width/height. Fit the terrain and feature labels to the setting.""");

        return ChatClient.create(chatModel).prompt()
                .system(SYSTEM)
                .user(prompt.toString())
                .call()
                .entity(SceneLayout.class);
    }

    /** A couple of recent player actions for flavour grounding; tolerant of any read failure. */
    private String recentContext(UUID sessionId) {
        try {
            List<TurnEvent> turns = ragService.getRecentTurns(sessionId); // newest first
            if (turns == null || turns.isEmpty()) {
                return "";
            }
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (TurnEvent t : turns) {
                if (t.getAction() == null || t.getAction().isBlank()) {
                    continue;
                }
                b.append("- ").append(trim(t.getAction(), 160)).append("\n");
                if (++n >= 3) {
                    break;
                }
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
