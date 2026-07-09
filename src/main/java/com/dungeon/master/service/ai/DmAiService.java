package com.dungeon.master.service.ai;

import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.model.dto.TravelContext;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.config.AiConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmAiService {

    private final ToolCallingManager toolCallingManager;
    private final DmRollTools dmRollTools;
    private final DmCampaignTools dmCampaignTools;
    private final DmQuestTools dmQuestTools;
    private final DmNpcTools dmNpcTools;
    private final RagService ragService;
    private final GameSessionRepository sessionRepository;
    private final EnemyRepository enemyRepository;
    private final DmPromptBuilder promptBuilder;
    private final ResilientChatStreamer chatStreamer;

    /** Result of one unified narrative turn: the assembled narration and whether any roll happened. */
    public record NarrativeTurnResult(String narration, boolean rolled) {}

    /**
     * Resolves a whole narrative turn in ONE DM turn. The party's action(s) are sent with the dice
     * tools attached; the AI DM decides any checks and CALLS the engine tools ({@code rollCheck} /
     * {@code groupCheck} / {@code contest}), which roll authoritatively and broadcast the dice, then
     * the model narrates one cohesive resolution from the results. A single action is a one-element
     * list; a collaborative round passes every contributor.
     *
     * <p>The decision round runs with tools enabled (a tool-call response carries no prose, so
     * nothing leaks to players before the roll); the narration round runs with tools disabled so it
     * cannot roll again. Tool execution (the only side effect) sits outside the streaming retry, so a
     * transient 429 never double-rolls.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackNarrativeTurn")
    public NarrativeTurnResult generateNarrativeTurn(UUID sessionId, List<Contribution> actions,
                                                     TravelContext travel,
                                                     Consumer<String> onChunk) {
        log.info("Streaming narrative turn for session={}, actions={}", sessionId, actions.size());

        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        // Travel narration must never roll checks — a check would suppress the encounter tag downstream.
        boolean travelTurn = travel != null;
        boolean allowRolls = (session == null || session.isAllowAiRolls()) && !travelTurn;
        boolean allowDisposition = session != null && session.isAllowAiDisposition() && !travelTurn;
        boolean toolsAllowed = allowRolls || allowDisposition;

        String combinedForContext = actions.stream()
                .filter(c -> !c.passed())
                .map(Contribution::action)
                .collect(Collectors.joining(" "));
        String context = ragService.buildContext(sessionId, combinedForContext);
        String userMessage = promptBuilder.sessionDirectives(sessionId)
                + promptBuilder.partySituation(sessionId)
                + promptBuilder.buildTurnUserMessage(actions, context)
                + promptBuilder.travelDirective(travel);

        List<Message> baseMessages = List.of(new SystemMessage(AiConfig.DM_SYSTEM_PROMPT),
                new UserMessage(userMessage));

        StringBuilder assembled = new StringBuilder();

        // No tools enabled → plain streamed narration, no tools at all.
        if (!toolsAllowed) {
            chatStreamer.streamAggregate(new Prompt(baseMessages), assembled, onChunk);
            return new NarrativeTurnResult(assembled.toString(), false);
        }

        // Attach only the tool groups the session enables: roll/campaign tools with AI rolls, the
        // NPC disposition tool with AI relationships — so either can work independently of the other.
        List<Object> tools = new ArrayList<>();
        if (allowRolls) {
            tools.add(dmRollTools);
            tools.add(dmCampaignTools);
            tools.add(dmQuestTools);
        }
        if (allowDisposition) {
            tools.add(dmNpcTools);
        }

        ToolCallingChatOptions toolOpts = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(tools.toArray()))
                .toolContext(promptBuilder.buildToolContext(sessionId, session))
                .internalToolExecutionEnabled(false)
                .build();

        Prompt decisionPrompt = new Prompt(baseMessages, toolOpts);
        ChatResponse resp = chatStreamer.streamAggregate(decisionPrompt, assembled, onChunk);

        boolean rolled = false;
        if (resp != null && resp.hasToolCalls()) {
            ToolExecutionResult exec = toolCallingManager.executeToolCalls(decisionPrompt, resp);
            // A disposition tweak isn't a "roll" — only real rolls/milestones suppress the downstream
            // encounter tag, so a soured NPC can still escalate to combat in the same turn.
            rolled = resp.getResult().getOutput().getToolCalls().stream()
                    .anyMatch(tc -> !"adjustDisposition".equals(tc.name()));
            // Narration round: tools disabled so it can only narrate the results, never roll again.
            ToolCallingChatOptions narrationOpts = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false)
                    .build();
            Prompt narrationPrompt = new Prompt(new ArrayList<>(exec.conversationHistory()), narrationOpts);
            chatStreamer.streamAggregate(narrationPrompt, assembled, onChunk);
        }

        log.info("Narrative turn done for session={}, rolled={}, length={}",
                sessionId, rolled, assembled.length());
        return new NarrativeTurnResult(assembled.toString(), rolled);
    }

    /**
     * Generates the opening scene when a game starts, streaming tokens to {@code onChunk}.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackOpening")
    public String generateOpening(UUID sessionId, String worldSetting, String priorRecap,
                                  Consumer<String> onChunk) {
        log.info("Generating opening narration for session={}", sessionId);

        StringBuilder prompt = new StringBuilder();
        prompt.append(promptBuilder.sessionDirectives(sessionId));
        String roster = promptBuilder.partyRoster(sessionId);
        if (!roster.isBlank()) {
            prompt.append("The party consists of EXACTLY these characters — present them as already ")
                    .append("together in the scene, and do NOT invent any other party members or give ")
                    .append("them names:\n").append(roster).append("\n");
        }
        prompt.append("The adventure is about to begin. ");
        if (worldSetting != null && !worldSetting.isBlank()) {
            prompt.append("World setting:\n").append(worldSetting).append("\n\n");
        }
        // Continuing a campaign: ground the opening in what happened last time so it reads as the next
        // chapter, not a fresh start. This is a recap of PRIOR sessions, not the current scene.
        if (priorRecap != null && !priorRecap.isBlank()) {
            prompt.append("Previously, on this campaign (recap of earlier sessions — continue from here, ")
                    .append("do not contradict it):\n").append(priorRecap).append("\n\n");
        }
        prompt.append("Narrate an immersive opening scene that sets the mood, establishes where ")
                .append("the party finds itself, and ends by inviting the players to describe what they ")
                .append("do. Refer to characters only by the exact names above; do not take actions on ")
                .append("the players' behalf.");

        String response = chatStreamer.streamToString(prompt.toString(), onChunk);

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

        // Fetch the encounter roster once and share it between the two enemy-aware helpers
        // so the combat path makes a single enemies query per beat.
        List<Enemy> enemies = enemyRepository.findBySessionId(sessionId);

        String userMessage = """
                Combat is underway. The following beat has ALREADY been resolved by the game \
                engine — these dice results, hits, misses, damage values, current HP, and any \
                deaths are FINAL and authoritative. Narrate what happened vividly and \
                dramatically, but do NOT change, contradict, or re-roll any outcome, and do \
                not invent new attacks. Keep it under 120 words. Refer to combatants by name. \
                If the beat marks an attack as "a critical hit", amplify it dramatically as a \
                spectacular, devastating blow; if it marks an attack as "a fumble", play it as a \
                costly, embarrassing stumble. Scale your drama to the damage dealt: a "devastating \
                blow" or a large damage figure relative to the target's HP should read as a brutal, \
                momentum-shifting strike, while a "glancing hit" or only a few points of damage \
                should read as a weak, partly-deflected blow that barely fazes the target — but in \
                every case ONLY add flavour, never new damage, deaths, or mechanical outcomes \
                beyond what the beat states.
                %s%s
                Resolved combat beat:
                %s""".formatted(promptBuilder.partySituation(sessionId, enemies),
                        promptBuilder.monsterLoreBlock(enemies), beatSummary);

        String response = chatStreamer.streamToString(userMessage, onChunk);

        log.info("Combat narration generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Writes a concise narrative chronicle of a finished session to hand to the next one. One
     * tool-free LLM call over pre-assembled source text ({@code sourceText} — party + narrative turns
     * + notable combat outcomes, built by {@code SessionRecapService}). Story only: no dice, no
     * mechanics, no blow-by-blow. Streams tokens so the UI can show it forming.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackSessionRecap")
    public String generateSessionRecap(UUID sessionId, String sourceText, Consumer<String> onChunk) {
        log.info("Generating session recap for session={}", sessionId);

        String prompt = """
                The adventuring session below has ended. Write a cohesive PAST-TENSE chronicle recap \
                (about 200-300 words) to hand to the Dungeon Master of the NEXT session so the story \
                continues seamlessly. Cover: where the party went, what they discovered, the key \
                decisions they made, notable NPCs met, and any unresolved threads or looming dangers. \
                Mention combat ONLY for consequential outcomes (a foe slain, an ally who fell, a narrow \
                escape) — never blow-by-blow rolls or numbers. Do NOT use game mechanics, dice, HP, or \
                stat language. Write it as an evocative "story so far", not a bullet list.

                %s""".formatted(sourceText);

        String response = chatStreamer.streamToString(prompt, onChunk);

        log.info("Session recap generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    @SuppressWarnings("unused")
    private String fallbackSessionRecap(UUID sessionId, String sourceText,
                                        Consumer<String> onChunk, Throwable throwable) {
        log.error("AI session recap unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        // No recap is better than a broken one — return empty so the handoff simply carries nothing
        // forward rather than a stack trace or partial text.
        return "";
    }

    @SuppressWarnings("unused")
    private NarrativeTurnResult fallbackNarrativeTurn(UUID sessionId, List<Contribution> actions,
                                                      TravelContext travel,
                                                      Consumer<String> onChunk, Throwable throwable) {
        log.error("AI narrative turn unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = "The Dungeon Master pauses, gathering their thoughts... " +
                "[The AI service is temporarily unavailable. The party's actions have been recorded. " +
                "The DM will respond when service is restored. Please try again in a moment.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return new NarrativeTurnResult(message, false);
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
    private String fallbackOpening(UUID sessionId, String worldSetting, String priorRecap,
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
