package com.dungeon.master.service.game;

import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.TurnSource;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.TurnEventRepository;
import com.dungeon.master.service.ai.DmAiService;
import com.dungeon.master.service.ai.DmPromptBuilder;
import com.dungeon.master.service.ai.DmTags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Builds the end-of-session narrative recap and resolves the recap handed to a continuing session.
 *
 * <p>The recap is story-only: {@link TurnSource#NARRATIVE} turns are kept verbatim, while
 * {@link TurnSource#COMBAT} beats are dropped except for a terse "notable outcomes" block (deaths,
 * defeats, escapes) — combat blow-by-blow doesn't move the story and would only cost tokens.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionRecapService {

    /** Bound the single recap prompt so a marathon session can't blow it up (chunking is future work). */
    private static final int MAX_NARRATIVE_TURNS = 100;

    /** Markers that flag a combat beat as a consequential outcome worth carrying into the story. */
    private static final String[] OUTCOME_MARKERS = {
            "defeated", "slain", "slays", "killed", "dies", "falls", "drops to 0", "goes down",
            "unconscious", "dying", "death save", "flee", "flees", "retreat", "surrender", "victor"
    };

    private final TurnEventRepository turnEventRepository;
    private final GameSessionRepository sessionRepository;
    private final DmAiService dmAiService;
    private final DmPromptBuilder promptBuilder;

    /**
     * Generate the chronicle for a finished session and persist it on the session. Streams tokens via
     * {@code onChunk}. Returns the recap text ("" when there was nothing to summarize or the LLM
     * fell back), never throwing — a failed recap must not break the end-of-session flow.
     */
    @Transactional
    public String generateAndStore(java.util.UUID sessionId, Consumer<String> onChunk) {
        List<TurnEvent> turns = turnEventRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
        String sourceText = buildSourceText(sessionId, turns);
        if (sourceText.isBlank()) {
            log.info("No content to recap for session={}", sessionId);
            return "";
        }

        String recap = DmTags.strip(dmAiService.generateSessionRecap(sessionId, sourceText, onChunk));
        if (recap.isBlank()) {
            return "";
        }

        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setRecap(recap);
            session.setRecapGeneratedAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
        log.info("Recap stored for session={}, length={}", sessionId, recap.length());
        return recap;
    }

    /**
     * The recap this session should inherit, or {@code null} for a fresh campaign. Precedence: an
     * explicit {@code continuedFromSessionId} wins; otherwise the most recent finished session of the
     * same saved World (same host).
     */
    public String priorRecapFor(GameSession session) {
        if (session.getContinuedFromSessionId() != null) {
            String recap = sessionRepository.findById(session.getContinuedFromSessionId())
                    .map(GameSession::getRecap)
                    .orElse(null);
            if (recap != null && !recap.isBlank()) {
                return recap;
            }
        }
        if (session.getWorldId() != null && session.getCreatedBy() != null) {
            return sessionRepository.findTopByWorldIdAndStatusAndCreatedByOrderByCreatedAtDesc(
                            session.getWorldId(), GameStatus.FINISHED, session.getCreatedBy())
                    .map(GameSession::getRecap)
                    .filter(r -> r != null && !r.isBlank())
                    .orElse(null);
        }
        return null;
    }

    /**
     * Assemble the recap source: the party roster, the narrative transcript (most recent
     * {@value #MAX_NARRATIVE_TURNS} narrative turns, in order), and a terse combat-outcomes block.
     */
    private String buildSourceText(java.util.UUID sessionId, List<TurnEvent> turns) {
        List<TurnEvent> narrative = turns.stream()
                .filter(t -> t.getSource() == TurnSource.NARRATIVE)
                .toList();
        if (narrative.size() > MAX_NARRATIVE_TURNS) {
            narrative = narrative.subList(narrative.size() - MAX_NARRATIVE_TURNS, narrative.size());
        }

        List<String> combatOutcomes = turns.stream()
                .filter(t -> t.getSource() == TurnSource.COMBAT)
                .map(SessionRecapService::outcomeLine)
                .filter(s -> s != null)
                .distinct()
                .toList();

        if (narrative.isEmpty() && combatOutcomes.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        String roster = promptBuilder.partyRoster(sessionId);
        if (!roster.isBlank()) {
            b.append("Party:\n").append(roster).append("\n\n");
        }

        if (!narrative.isEmpty()) {
            b.append("Story so far (player actions and the DM's responses, in order):\n");
            for (TurnEvent t : narrative) {
                b.append("- Action: ").append(t.getAction());
                if (t.getDmResponse() != null && !t.getDmResponse().isBlank()) {
                    b.append("\n  DM: ").append(t.getDmResponse());
                }
                b.append("\n");
            }
            b.append("\n");
        }

        if (!combatOutcomes.isEmpty()) {
            b.append("Notable combat outcomes:\n");
            for (String line : combatOutcomes) {
                b.append("- ").append(line).append("\n");
            }
        }
        return b.toString();
    }

    /**
     * A combat beat's mechanical summary if it reads as a consequential outcome (a death, defeat,
     * flight…), else {@code null} so routine exchanges are dropped from the recap.
     */
    private static String outcomeLine(TurnEvent beat) {
        String text = beat.getAction();
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : OUTCOME_MARKERS) {
            if (lower.contains(marker)) {
                return text.trim();
            }
        }
        return null;
    }
}
