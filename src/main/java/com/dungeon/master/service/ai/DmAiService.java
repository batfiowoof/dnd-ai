package com.dungeon.master.service.ai;

import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.PlayerRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmAiService {

    private final ChatClient dmChatClient;
    private final RagService ragService;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;

    /**
     * Generates a DM response to a player's action, streaming each token to {@code onChunk}
     * as it arrives and returning the fully-assembled narration once complete.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackStreaming")
    public String generateResponseStreaming(UUID sessionId, UUID playerId, String playerName,
                                            String playerAction, Consumer<String> onChunk) {
        log.info("Streaming DM response for session={}, player={}", sessionId, playerName);

        String context = ragService.buildContext(sessionId, playerAction);
        String characterBlock = characterContext(playerId);
        String userMessage = buildUserMessage(context, characterBlock, playerName, playerAction);

        String response = streamToString(userMessage, onChunk);

        log.info("DM response generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Generates the opening scene when a game starts, streaming tokens to {@code onChunk}.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackOpening")
    public String generateOpening(UUID sessionId, String worldSetting, Consumer<String> onChunk) {
        log.info("Generating opening narration for session={}", sessionId);

        StringBuilder prompt = new StringBuilder();
        prompt.append("The adventure is about to begin for a party of players. ");
        if (worldSetting != null && !worldSetting.isBlank()) {
            prompt.append("World setting:\n").append(worldSetting).append("\n\n");
        }
        prompt.append("Narrate an immersive opening scene that sets the mood, establishes where ")
                .append("the party finds itself, and ends by inviting the first player to describe ")
                .append("what they do. Do not take actions on the players' behalf.");

        String response = streamToString(prompt.toString(), onChunk);

        log.info("Opening narration generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Narrates a combat "beat" whose outcome has already been resolved authoritatively by
     * the combat engine. The model adds flavor only — it must not change hits, numbers, or
     * who lives or dies. The {@code beatSummary} carries the mechanical truth (rolls, hits,
     * damage, current HP) so the narration can describe it accurately ("bloodied", "staggers").
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackCombatNarration")
    public String generateCombatNarration(UUID sessionId, String beatSummary,
                                           Consumer<String> onChunk) {
        log.info("Streaming combat narration for session={}", sessionId);

        String userMessage = """
                Combat is underway. The following beat has ALREADY been resolved by the game \
                engine — these dice results, hits, misses, damage values, current HP, and any \
                deaths are FINAL and authoritative. Narrate what happened vividly and \
                dramatically, but do NOT change, contradict, or re-roll any outcome, and do \
                not invent new attacks. Keep it under 120 words. Refer to combatants by name.

                Resolved combat beat:
                %s""".formatted(beatSummary);

        String response = streamToString(userMessage, onChunk);

        log.info("Combat narration generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    private String streamToString(String userMessage, Consumer<String> onChunk) {
        StringBuilder assembled = new StringBuilder();
        dmChatClient.prompt()
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    assembled.append(chunk);
                    if (onChunk != null) {
                        onChunk.accept(chunk);
                    }
                })
                .blockLast();
        return assembled.toString();
    }

    private String buildUserMessage(String context, String characterBlock,
                                    String playerName, String action) {
        StringBuilder message = new StringBuilder();

        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n");
            message.append(context);
            message.append("\n---\n\n");
        }

        if (!characterBlock.isBlank()) {
            message.append(characterBlock).append("\n");
        }

        message.append("Player '").append(playerName).append("' says: ").append(action);

        return message.toString();
    }

    /**
     * Builds a one-line summary of the acting player's character so the DM can narrate with
     * awareness of class, level, and combat stats. Returns an empty string if unavailable.
     */
    private String characterContext(UUID playerId) {
        if (playerId == null) {
            return "";
        }
        Player player = playerRepository.findById(playerId).orElse(null);
        if (player == null || player.getCharacterId() == null) {
            return "";
        }
        Character c = characterRepository.findById(player.getCharacterId()).orElse(null);
        if (c == null) {
            return "";
        }
        return "Acting character: " + c.getName()
                + " (Level " + c.getLevel() + " " + c.getRace() + " " + c.getCharacterClass() + ")"
                + " — HP " + c.getHitPoints() + ", AC " + c.getArmorClass()
                + ", STR " + c.getStrength() + " DEX " + c.getDexterity() + " CON " + c.getConstitution()
                + " INT " + c.getIntelligence() + " WIS " + c.getWisdom() + " CHA " + c.getCharisma() + ".";
    }

    @SuppressWarnings("unused")
    private String fallbackStreaming(UUID sessionId, UUID playerId, String playerName,
                                     String playerAction, Consumer<String> onChunk,
                                     Throwable throwable) {
        log.error("AI service unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = "The Dungeon Master pauses, gathering their thoughts... " +
                "[The AI service is temporarily unavailable. " +
                "Your action '" + playerAction + "' has been recorded. " +
                "The DM will respond when service is restored. Please try again in a moment.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }

    @SuppressWarnings("unused")
    private String fallbackCombatNarration(UUID sessionId, String beatSummary,
                                           Consumer<String> onChunk, Throwable throwable) {
        log.error("AI combat narration unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        // Fall back to the raw mechanical summary so combat still narrates *something* and
        // never blocks on the LLM. Combat mechanics have already resolved authoritatively.
        String message = beatSummary == null || beatSummary.isBlank()
                ? "The clash of combat rings out."
                : beatSummary;
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }

    @SuppressWarnings("unused")
    private String fallbackOpening(UUID sessionId, String worldSetting,
                                   Consumer<String> onChunk, Throwable throwable) {
        log.error("AI opening narration unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = "The adventure begins, though the Dungeon Master is still gathering their " +
                "thoughts... [The AI service is temporarily unavailable. Describe what your character " +
                "does to get things rolling.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }
}
