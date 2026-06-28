package com.dungeon.master.service.ai;

import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.config.AiConfig;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CheckModifierService;
import com.dungeon.master.service.game.PlayerStateService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmAiService {

    private final ChatClient dmChatClient;
    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final DmRollTools dmRollTools;
    private final CheckModifierService checkModifierService;
    private final RagService ragService;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final GameSessionRepository sessionRepository;
    private final EnemyRepository enemyRepository;
    private final CombatEncounterRepository encounterRepository;
    private final PlayerStateService playerStateService;
    private final SrdContent srdContent;
    private final com.dungeon.master.service.game.MonsterCatalog monsterCatalog;

    /** Result of one unified narrative turn: the assembled narration and whether any roll happened. */
    public record NarrativeTurnResult(String narration, boolean rolled) {}

    // Cloud chat (OpenRouter) has transient failures a local model didn't — chiefly 429
    // rate-limit/congestion. Retry the streamed call with exponential backoff before letting
    // the circuit breaker fall back. Backoff: 2s, 4s, 8s (capped at 10s).
    private static final int AI_MAX_RETRIES = 3;
    private static final Duration AI_RETRY_MIN_BACKOFF = Duration.ofSeconds(2);
    private static final Duration AI_RETRY_MAX_BACKOFF = Duration.ofSeconds(10);

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
                                                     Map<UUID, Boolean> spendInspiration,
                                                     Consumer<String> onChunk) {
        log.info("Streaming narrative turn for session={}, actions={}", sessionId, actions.size());

        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        boolean allowRolls = session == null || session.isAllowAiRolls();

        String combinedForContext = actions.stream()
                .filter(c -> !c.passed())
                .map(Contribution::action)
                .collect(Collectors.joining(" "));
        String context = ragService.buildContext(sessionId, combinedForContext);
        String userMessage = sessionDirectives(sessionId)
                + partySituation(sessionId)
                + buildTurnUserMessage(actions, context);

        List<Message> baseMessages = List.of(new SystemMessage(AiConfig.DM_SYSTEM_PROMPT),
                new UserMessage(userMessage));

        StringBuilder assembled = new StringBuilder();

        // No rolls allowed → plain streamed narration, no tools at all.
        if (!allowRolls) {
            streamAggregate(new Prompt(baseMessages), assembled, onChunk);
            return new NarrativeTurnResult(assembled.toString(), false);
        }

        ToolCallingChatOptions toolOpts = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(dmRollTools))
                .toolContext(buildToolContext(sessionId, session, spendInspiration))
                .internalToolExecutionEnabled(false)
                .build();

        Prompt decisionPrompt = new Prompt(baseMessages, toolOpts);
        ChatResponse resp = streamAggregate(decisionPrompt, assembled, onChunk);

        boolean rolled = false;
        if (resp != null && resp.hasToolCalls()) {
            ToolExecutionResult exec = toolCallingManager.executeToolCalls(decisionPrompt, resp);
            rolled = true;
            // Narration round: tools disabled so it can only narrate the results, never roll again.
            ToolCallingChatOptions narrationOpts = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false)
                    .build();
            Prompt narrationPrompt = new Prompt(new ArrayList<>(exec.conversationHistory()), narrationOpts);
            streamAggregate(narrationPrompt, assembled, onChunk);
        }

        log.info("Narrative turn done for session={}, rolled={}, length={}",
                sessionId, rolled, assembled.length());
        return new NarrativeTurnResult(assembled.toString(), rolled);
    }

    /** Build the acting block: a single action reads naturally; a round lists every contributor. */
    private String buildTurnUserMessage(List<Contribution> actions, String context) {
        StringBuilder message = new StringBuilder();
        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n").append(context).append("\n---\n\n");
        }
        if (actions.size() == 1 && !actions.get(0).passed()) {
            Contribution c = actions.get(0);
            message.append("Player '").append(c.characterName()).append("' says: ").append(c.action());
        } else {
            message.append("The party acts together this round. Resolve every character's action in a ")
                    .append("single cohesive reply, addressing each by name and weaving their actions ")
                    .append("into one unfolding scene. Do not invent actions for anyone who held back.\n\n")
                    .append("This round's actions:\n");
            for (Contribution c : actions) {
                if (c.passed()) {
                    message.append("- ").append(c.characterName()).append(" holds back and observes.\n");
                } else {
                    message.append("- ").append(c.characterName()).append(": ").append(c.action()).append("\n");
                }
            }
        }
        return message.toString();
    }

    /** Per-turn context handed to the dice tools: session, name→player map, Inspiration intent, DCs. */
    private Map<String, Object> buildToolContext(UUID sessionId, GameSession session,
                                                 Map<UUID, Boolean> spendInspiration) {
        Map<String, UUID> nameToPlayer = new HashMap<>();
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER) {
                continue;
            }
            putName(nameToPlayer, p.getCharacterName(), p.getId());
            putName(nameToPlayer, p.getUsername(), p.getId());
        }
        com.dungeon.master.model.enums.Difficulty diff =
                session == null ? null : session.getDifficulty();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(DmRollTools.K_SESSION, sessionId);
        ctx.put(DmRollTools.K_NAME_TO_PLAYER, nameToPlayer);
        ctx.put(DmRollTools.K_SPEND_INSP, spendInspiration == null ? Map.of() : spendInspiration);
        ctx.put(DmRollTools.K_DEFAULT_DC, CheckModifierService.defaultDc(diff));
        ctx.put(DmRollTools.K_DEFAULT_CONTEST_MOD, CheckModifierService.defaultContestMod(diff));
        return ctx;
    }

    private static void putName(Map<String, UUID> map, String name, UUID id) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        map.putIfAbsent(key, id);
        int sp = key.indexOf(' ');
        if (sp > 0) {
            map.putIfAbsent(key.substring(0, sp), id); // first token, e.g. "aria" ← "aria brightblade"
        }
    }

    /**
     * Generates the opening scene when a game starts, streaming tokens to {@code onChunk}.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackOpening")
    public String generateOpening(UUID sessionId, String worldSetting, Consumer<String> onChunk) {
        log.info("Generating opening narration for session={}", sessionId);

        StringBuilder prompt = new StringBuilder();
        prompt.append(sessionDirectives(sessionId));
        String roster = partyRoster(sessionId);
        if (!roster.isBlank()) {
            prompt.append("The party consists of EXACTLY these characters — present them as already ")
                    .append("together in the scene, and do NOT invent any other party members or give ")
                    .append("them names:\n").append(roster).append("\n");
        }
        prompt.append("The adventure is about to begin. ");
        if (worldSetting != null && !worldSetting.isBlank()) {
            prompt.append("World setting:\n").append(worldSetting).append("\n\n");
        }
        prompt.append("Narrate an immersive opening scene that sets the mood, establishes where ")
                .append("the party finds itself, and ends by inviting the players to describe what they ")
                .append("do. Refer to characters only by the exact names above; do not take actions on ")
                .append("the players' behalf.");

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
                costly, embarrassing stumble — but in either case ONLY add flavour, never new \
                damage, deaths, or mechanical outcomes beyond what the beat states.
                %s%s
                Resolved combat beat:
                %s""".formatted(partySituation(sessionId, enemies), monsterLoreBlock(enemies), beatSummary);

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
                .retryWhen(Retry.backoff(AI_MAX_RETRIES, AI_RETRY_MIN_BACKOFF)
                        .maxBackoff(AI_RETRY_MAX_BACKOFF)
                        // Retry only transient failures, and only while nothing has streamed yet —
                        // a re-subscribe re-runs the prompt from scratch, so retrying after tokens
                        // have reached the client would duplicate narration.
                        .filter(t -> assembled.length() == 0 && isRetryable(t))
                        // Propagate the ORIGINAL error (not Reactor's RetryExhaustedException) so the
                        // @CircuitBreaker counts it and the fallback fires with the real cause.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .blockLast();
        return assembled.toString();
    }

    /**
     * Stream a {@link Prompt} through the raw {@link ChatModel}, forwarding any assistant TEXT to
     * {@code onChunk} and returning the aggregated {@link ChatResponse} (so the caller can inspect
     * tool calls). A tool-call response carries no text, so the decision round forwards nothing. The
     * same "retry only before the first token" guard as {@link #streamToString} keeps a transient
     * 429 from duplicating already-streamed narration; tool execution happens OUTSIDE this method, so
     * a retried decision round never re-rolls.
     */
    private ChatResponse streamAggregate(Prompt prompt, StringBuilder assembled, Consumer<String> onChunk) {
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        new MessageAggregator().aggregate(
                chatModel.stream(prompt)
                        .doOnNext(cr -> {
                            String text = textOf(cr);
                            if (text != null && !text.isEmpty()) {
                                assembled.append(text);
                                if (onChunk != null) {
                                    onChunk.accept(text);
                                }
                            }
                        })
                        .retryWhen(Retry.backoff(AI_MAX_RETRIES, AI_RETRY_MIN_BACKOFF)
                                .maxBackoff(AI_RETRY_MAX_BACKOFF)
                                .filter(t -> assembled.length() == 0 && isRetryable(t))
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure())),
                aggregated::set)
                .blockLast();
        return aggregated.get();
    }

    private static String textOf(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return null;
        }
        return cr.getResult().getOutput().getText();
    }

    /** Transient cloud failures worth retrying: 429 rate limits, 5xx, and connection blips. */
    private static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
        }
        return t instanceof TransientAiException
                || t instanceof WebClientRequestException
                || t instanceof java.io.IOException;
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
     * Builds the per-session "Session directives" block prepended to every DM user message.
     * The static system prompt cannot carry per-session data, so tone, verbosity, difficulty
     * DC guidance, and the AI-combat/roll toggles are injected here from the session settings.
     * Returns a trailing-newline-terminated block (empty string only if the session is gone).
     */
    private String sessionDirectives(UUID sessionId) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("Session directives (override the base guidance where they conflict):\n");
        b.append("- Tone: ").append(toneGuidance(session.getDmStyle())).append("\n");
        b.append("- Length: ").append(lengthGuidance(session.getDmLength())).append("\n");
        b.append("- Difficulty: ").append(difficultyGuidance(session.getDifficulty())).append("\n");
        if (session.isAllowAiRolls()) {
            b.append("- Ability checks: when a narrative action's outcome is genuinely uncertain ")
                    .append("(not trivial), CALL the rollCheck tool for the acting character instead of ")
                    .append("narrating success or failure — emit only the tool call, no prose first. ")
                    .append("Routine actions get narrated normally with no tool call. Use ADVANTAGE or ")
                    .append("DISADVANTAGE only when the situation clearly warrants it; the player's own ")
                    .append("lever is spending Inspiration, applied by the engine.\n");
            b.append("- Group checks: when the SAME uncertain task faces the whole party at once (everyone ")
                    .append("sneaks past, all swim the rapids), call the groupCheck tool. Every player rolls; ")
                    .append("the engine applies the rule that the party succeeds only if at least half succeed.\n");
            b.append("- Contests: when ONE character is directly opposed by an NPC (an arm-wrestle, a ")
                    .append("stealth-vs-perception, a shove), call the contest tool. The engine rolls BOTH ")
                    .append("sides and decides the winner (ties favour the defender); pass targetMod=0 to let ")
                    .append("the engine set a fair one.\n");
            b.append("- Inspiration: reward standout play sparingly by ending a reply with an award tag on ")
                    .append("its own final line — [[INSPIRATION: player=\"<character name>\" reason=\"clever, brave, or great roleplay\"]]. ")
                    .append("Always wrap the character's name in double quotes so multi-word names survive; ")
                    .append("award at most one per reply and never narrate the raw tag.\n");
        } else {
            b.append("- Ability checks: do NOT request dice rolls; narrate outcomes directly and fairly.\n");
        }
        if (session.isAllowAiCombat()) {
            String enemyKeys = monsterCatalog.isEmpty()
                    ? String.join(", ", com.dungeon.master.service.game.Bestiary.keys())
                    : String.join(", ", monsterCatalog.promptKeys(40));
            b.append("- Combat: when the story leads to a fight, END your reply with an encounter tag on ")
                    .append("its own final line — [[ENCOUNTER: GOBLIN_WARRIOR x2, WOLF]] — using ONLY these enemy keys: ")
                    .append(enemyKeys).append(".\n");
        } else {
            b.append("- Combat: do NOT start encounters with tags; the host triggers combat manually.\n");
        }
        b.append("---\n\n");
        return b.toString();
    }

    private String toneGuidance(DmStyle style) {
        return switch (style) {
            case HEROIC -> "heroic high-fantasy — sweeping, hopeful, larger-than-life.";
            case GRIMDARK -> "grimdark — bleak, morally grey, tense and unforgiving.";
            case COMEDIC -> "comedic — witty, irreverent, playful (without breaking the world).";
        };
    }

    private String lengthGuidance(DmLength length) {
        return switch (length) {
            case CONCISE -> "concise — roughly 60–100 words.";
            case STANDARD -> "standard — roughly 120–180 words.";
            case RICH -> "rich and detailed — roughly 200–280 words.";
        };
    }

    private String difficultyGuidance(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "forgiving — favor the players; use easy DCs (about 8–12) when you call for a roll.";
            case NORMAL -> "balanced — use standard DCs (about 12–16) when you call for a roll.";
            case DEADLY -> "harsh — raise the stakes; use hard DCs (about 16–20) when you call for a roll.";
        };
    }

    /**
     * Direct-injects SRD lore for the distinct monsters in the current encounter so combat
     * narration is grounded in how each creature actually fights. The combat beat is a mechanical
     * summary (no player prose), so semantic retrieval wouldn't surface this — but the engine
     * already knows the roster. Returns a leading-newline block, or "" when no lore is available.
     */
    private String monsterLoreBlock(List<Enemy> enemies) {
        java.util.Set<String> seenTitles = new java.util.LinkedHashSet<>();
        StringBuilder b = new StringBuilder();
        for (Enemy enemy : enemies) {
            srdContent.monsterEntryByName(enemy.getName()).ifPresent(entry -> {
                if (seenTitles.add(entry.title())) { // one line per monster type, not per instance
                    b.append("- ").append(entry.title()).append(": ")
                            .append(entry.content()).append("\n");
                }
            });
        }
        if (b.isEmpty()) {
            return "";
        }
        return "\nMonster reference (flavour only — stats and outcomes are the engine's):\n" + b;
    }

    /**
     * Builds a COMPACT, situational snapshot of the whole party (and any active enemies) so the
     * DM can ground tactical narration — who is hurt, who is afflicted, what they're up against.
     * Purely informative: HP is given as a coarse band (never raw numbers), and the block is
     * explicitly flagged as context, not a license to change any engine-owned outcome. Resilient
     * by design — a player whose runtime state was never seeded is simply skipped. Returns "" when
     * there is nothing useful to say, so callers can omit the block entirely.
     */
    String partySituation(UUID sessionId) {
        return partySituation(sessionId, enemyRepository.findBySessionId(sessionId));
    }

    /**
     * A compact cast list (character name + class/level) for the opening scene, where runtime
     * state may not be seeded yet — so it reads only the Player/Character roster, not HP/conditions.
     */
    private String partyRoster(UUID sessionId) {
        StringBuilder b = new StringBuilder();
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER) {
                continue;
            }
            b.append("- ").append(partyMemberName(p));
            String classLevel = classLevel(p);
            if (classLevel != null) {
                b.append(" (").append(classLevel).append(")");
            }
            b.append("\n");
        }
        return b.toString();
    }

    /**
     * Variant that reuses a pre-fetched enemy roster so the combat path can query enemies once
     * and share the list with {@link #monsterLoreBlock(List)}.
     */
    String partySituation(UUID sessionId, List<Enemy> enemies) {
        List<Player> players = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .toList();

        StringBuilder members = new StringBuilder();
        for (Player p : players) {
            PlayerRuntimeStateDto state;
            try {
                state = playerStateService.getState(p.getId());
            } catch (Exception e) {
                // No runtime state seeded for this player yet — skip rather than fail the block.
                continue;
            }
            if (state == null) {
                continue;
            }
            members.append("- ").append(partyMemberName(p));
            String classLevel = classLevel(p);
            if (classLevel != null) {
                members.append(" (").append(classLevel).append(")");
            }
            members.append(" — ").append(hpBand(state.currentHp(), state.maxHp()));
            if (state.inspiration()) {
                members.append(" (Inspired)");
            }
            List<String> conditions = state.conditions();
            if (conditions != null && !conditions.isEmpty()) {
                members.append(", [").append(String.join(", ", conditions)).append("]");
            }
            members.append("\n");
        }

        if (members.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        b.append("=== Party Situation — the COMPLETE party roster. These are the ONLY player ")
                .append("characters in the game; do NOT invent or name any others. Use these exact ")
                .append("names. (Situational context only — NOT a license to change any outcome.) ===\n");
        b.append(members);

        // Only surface the enemy roster while an encounter is genuinely ACTIVE. Ending an
        // encounter (TPK or host-forced) leaves surviving enemies with alive=true, so keying
        // off live enemies alone would inject a phantom "In combat:" line for the rest of the
        // session and make the DM narrate a fight that already ended.
        boolean combatActive = encounterRepository
                .findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE)
                .isPresent();
        if (combatActive) {
            List<Enemy> livingEnemies = enemies.stream()
                    .filter(Enemy::isAlive)
                    .toList();
            if (!livingEnemies.isEmpty()) {
                b.append("In combat: ");
                b.append(livingEnemies.stream()
                        .map(e -> e.getName() + " (" + hpBand(e.getCurrentHp(), e.getMaxHp()) + ")")
                        .collect(Collectors.joining(", ")));
                b.append("\n");
            }
        }

        b.append("---\n\n");
        return b.toString();
    }

    /** Coarse HP band so narration can say "bloodied"/"critical" without leaking exact numbers. */
    private static String hpBand(int currentHp, int maxHp) {
        if (currentHp <= 0) {
            return "down";
        }
        if (maxHp <= 0) {
            return "healthy";
        }
        double pct = (double) currentHp / maxHp;
        if (pct <= 0.25) {
            return "critical";
        }
        if (pct <= 0.50) {
            return "bloodied";
        }
        return "healthy";
    }

    /** In-world display name for a party member: character name when set, else the username. */
    private String partyMemberName(Player player) {
        if (player.getCharacterName() != null && !player.getCharacterName().isBlank()) {
            return player.getCharacterName();
        }
        return player.getUsername();
    }

    /** "Class Level" (e.g. "Fighter 3") for a party member, or null if no character is linked. */
    private String classLevel(Player player) {
        if (player.getCharacterId() == null) {
            return null;
        }
        Character c = characterRepository.findById(player.getCharacterId()).orElse(null);
        if (c == null) {
            return null;
        }
        return c.getCharacterClass() + " " + c.getLevel();
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
    private NarrativeTurnResult fallbackNarrativeTurn(UUID sessionId, List<Contribution> actions,
                                                      Map<UUID, Boolean> spendInspiration,
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
