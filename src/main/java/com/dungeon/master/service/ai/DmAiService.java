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
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.PlayerStateService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmAiService {

    private final ChatClient dmChatClient;
    private final RagService ragService;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final GameSessionRepository sessionRepository;
    private final EnemyRepository enemyRepository;
    private final CombatEncounterRepository encounterRepository;
    private final PlayerStateService playerStateService;
    private final SrdContent srdContent;

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
        String userMessage = sessionDirectives(sessionId)
                + partySituation(sessionId)
                + buildUserMessage(context, characterBlock, playerName, playerAction);

        String response = streamToString(userMessage, onChunk);

        log.info("DM response generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Resolves a whole collaborative round in one streamed reply, addressing every acting
     * character by name. Exactly one LLM call per round — the {@code RoundCollector} guarantees
     * the actions never race.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackCollaborative")
    public String generateCollaborativeResponse(UUID sessionId, List<Contribution> actions,
                                                 Consumer<String> onChunk) {
        log.info("Streaming collaborative DM response for session={}, actions={}",
                sessionId, actions.size());

        String combinedForContext = actions.stream()
                .filter(c -> !c.passed())
                .map(Contribution::action)
                .collect(Collectors.joining(" "));
        String context = ragService.buildContext(sessionId, combinedForContext);

        StringBuilder roundBlock = new StringBuilder();
        roundBlock.append("The party acts together this round. Resolve every character's action in a ")
                .append("single cohesive reply, addressing each by name and weaving their actions into ")
                .append("one unfolding scene. Do not invent actions for anyone who held back.\n\n")
                .append("This round's actions:\n");
        for (Contribution c : actions) {
            if (c.passed()) {
                roundBlock.append("- ").append(c.characterName()).append(" holds back and observes.\n");
            } else {
                roundBlock.append("- ").append(c.characterName()).append(": ").append(c.action()).append("\n");
            }
        }

        StringBuilder message = new StringBuilder();
        message.append(sessionDirectives(sessionId));
        message.append(partySituation(sessionId));
        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n").append(context).append("\n---\n\n");
        }
        message.append(roundBlock);

        String response = streamToString(message.toString(), onChunk);
        log.info("Collaborative DM response generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Narrates the consequence of an ability check the engine has already rolled
     * authoritatively. Like combat narration, the model adds flavor only — it must honor the
     * given total vs DC and the success/failure verdict.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackCheckResolution")
    public String generateCheckResolution(UUID sessionId, UUID playerId, String action,
                                          String ability, String skill, int dc, int total,
                                          boolean success, Consumer<String> onChunk) {
        log.info("Streaming check resolution for session={}, success={}", sessionId, success);

        String context = ragService.buildContext(sessionId, action == null ? "" : action);
        String characterBlock = characterContext(playerId);
        String skillPart = (skill == null || skill.isBlank()) ? "" : " (" + skill + ")";

        StringBuilder message = new StringBuilder();
        message.append(sessionDirectives(sessionId));
        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n").append(context).append("\n---\n\n");
        }
        if (!characterBlock.isBlank()) {
            message.append(characterBlock).append("\n");
        }
        message.append("The character attempted: ").append(action == null ? "an uncertain action" : action)
                .append("\nThe game engine rolled their ").append(ability).append(skillPart)
                .append(" check: total ").append(total).append(" vs DC ").append(dc)
                .append(" — a ").append(success ? "SUCCESS" : "FAILURE")
                .append(". Narrate the consequence vividly and fairly from this result. The roll is ")
                .append("FINAL and authoritative — do not change it, re-roll, or contradict the verdict. ")
                .append("End by inviting the party to continue.");

        String response = streamToString(message.toString(), onChunk);
        log.info("Check resolution generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Narrates the outcomes of several ability checks rolled in the same collaborative round in
     * one cohesive reply. {@code checksSummary} lists each character's authoritative result
     * (total vs DC, success/failure) — the model must honor every verdict, not re-roll.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackBatchedCheckResolution")
    public String generateBatchedCheckResolution(UUID sessionId, String checksSummary,
                                                 Consumer<String> onChunk) {
        log.info("Streaming batched check resolution for session={}", sessionId);

        String context = ragService.buildContext(sessionId, checksSummary == null ? "" : checksSummary);

        StringBuilder message = new StringBuilder();
        message.append(sessionDirectives(sessionId));
        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n").append(context).append("\n---\n\n");
        }
        message.append("The party attempted several uncertain actions this round. The game engine has ")
                .append("ALREADY rolled each check authoritatively:\n")
                .append(checksSummary).append("\n")
                .append("Narrate all of these outcomes together in a single cohesive scene, addressing ")
                .append("each character by name. The rolls are FINAL — do not change, re-roll, or ")
                .append("contradict any verdict. End by inviting the party to continue.");

        String response = streamToString(message.toString(), onChunk);
        log.info("Batched check resolution generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Narrates the outcome of a GROUP check the engine has already rolled and adjudicated. The
     * {@code checksSummary} lists each participant's authoritative result; {@code successes}/{@code
     * total} and {@code groupSucceeded} carry the engine's half-the-party verdict. The model honors
     * the verdict — it never re-rolls or overturns it.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackGroupCheckResolution")
    public String generateGroupCheckResolution(UUID sessionId, String checksSummary, int successes,
                                               int total, boolean groupSucceeded,
                                               Consumer<String> onChunk) {
        log.info("Streaming group check resolution for session={}, succeeded={}", sessionId, groupSucceeded);

        String context = ragService.buildContext(sessionId, checksSummary == null ? "" : checksSummary);

        StringBuilder message = new StringBuilder();
        message.append(sessionDirectives(sessionId));
        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n").append(context).append("\n---\n\n");
        }
        message.append("The whole party attempted a GROUP check together. The game engine has ALREADY ")
                .append("rolled each participant authoritatively:\n")
                .append(checksSummary).append("\n")
                .append("By the group rule (the party succeeds only if at least half its members succeed), ")
                .append(successes).append(" of ").append(total).append(" succeeded, so the group ")
                .append(groupSucceeded ? "SUCCEEDS" : "FAILS").append(" as a whole. ")
                .append("Narrate this collective outcome in one cohesive scene, addressing members by name ")
                .append("and showing how the strong carried (or the weak undid) the effort. The rolls and ")
                .append("the group verdict are FINAL — do not change, re-roll, or contradict them. End by ")
                .append("inviting the party to continue.");

        String response = streamToString(message.toString(), onChunk);
        log.info("Group check resolution generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    /**
     * Narrates the outcome of a CONTEST the engine has already adjudicated by rolling BOTH sides.
     * {@code actorTotal} vs {@code targetTotal} and {@code actorWon} are authoritative (ties favour
     * the defender). The model adds flavour only — it never overturns the winner.
     */
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackContestResolution")
    public String generateContestResolution(UUID sessionId, String actorName, int actorTotal,
                                            String targetLabel, int targetTotal, boolean actorWon,
                                            Consumer<String> onChunk) {
        log.info("Streaming contest resolution for session={}, actorWon={}", sessionId, actorWon);

        String context = ragService.buildContext(sessionId, actorName + " vs " + targetLabel);

        StringBuilder message = new StringBuilder();
        message.append(sessionDirectives(sessionId));
        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n").append(context).append("\n---\n\n");
        }
        message.append("A contested roll pitted ").append(actorName).append(" directly against ")
                .append(targetLabel).append(". The game engine rolled BOTH sides authoritatively: ")
                .append(actorName).append(" scored ").append(actorTotal).append(", ")
                .append(targetLabel).append(" scored ").append(targetTotal).append(" — ")
                .append(actorWon ? actorName + " WINS the contest." : targetLabel + " WINS the contest.")
                .append(" Narrate this head-to-head outcome vividly and fairly. The totals and the ")
                .append("winner are FINAL — do not change, re-roll, or contradict them. End by inviting ")
                .append("the party to continue.");

        String response = streamToString(message.toString(), onChunk);
        log.info("Contest resolution generated for session={}, length={}", sessionId, response.length());
        return response;
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
                    .append("(not trivial), set the scene briefly and END your reply with a check tag on ")
                    .append("its own final line — [[ROLL: player=\"<character name>\" ability=DEX dc=15 ")
                    .append("skill=Acrobatics reason=\"...\"]] — instead of narrating success or failure. ")
                    .append("Routine actions get narrated normally. ")
                    .append("Add mode=ADVANTAGE or mode=DISADVANTAGE to the tag ONLY when the situation ")
                    .append("clearly warrants it (a favourable angle, or a real hindrance); omit it otherwise. ")
                    .append("The player can spend Inspiration for advantage — do not pick the player's mode for them.\n");
            b.append("- Group checks: when the SAME uncertain task faces the whole party at once (everyone ")
                    .append("sneaks past, all swim the rapids), END your reply with a group tag on its own ")
                    .append("final line — [[GROUP: ability=DEX dc=15 skill=Stealth reason=\"sneak past\"]]. ")
                    .append("Every player rolls; the engine applies the rule that the party succeeds only if ")
                    .append("at least half its members succeed. Do not name players in a group tag.\n");
            b.append("- Contests: when ONE character is directly opposed by an NPC (an arm-wrestle, a ")
                    .append("stealth-vs-perception, a shove), END your reply with a contest tag on its own ")
                    .append("final line — [[CONTEST: actor=\"<character name>\" actorAbility=DEX ")
                    .append("actorSkill=Stealth targetMod=3 targetLabel=\"the guard\" reason=\"slip past\"]]. ")
                    .append("The engine rolls BOTH sides and decides the winner (ties favour the defender). ")
                    .append("Quote the actor and targetLabel; omit targetMod if you are unsure and the engine ")
                    .append("will set a fair one.\n");
            b.append("- Inspiration: reward standout play sparingly by ending a reply with an award tag on ")
                    .append("its own final line — [[INSPIRATION: player=\"<character name>\" reason=\"clever, brave, or great roleplay\"]]. ")
                    .append("Always wrap the character's name in double quotes so multi-word names survive; ")
                    .append("award at most one per reply and never narrate the raw tag.\n");
        } else {
            b.append("- Ability checks: do NOT request dice rolls; narrate outcomes directly and fairly.\n");
        }
        if (session.isAllowAiCombat()) {
            b.append("- Combat: when the story leads to a fight, END your reply with an encounter tag on ")
                    .append("its own final line — [[ENCOUNTER: GOBLIN x2, ORC]] — using ONLY these enemy keys: ")
                    .append(String.join(", ", com.dungeon.master.service.game.Bestiary.keys())).append(".\n");
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
    private String fallbackCollaborative(UUID sessionId, List<Contribution> actions,
                                         Consumer<String> onChunk, Throwable throwable) {
        log.error("AI collaborative narration unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = "The Dungeon Master pauses, gathering their thoughts... " +
                "[The AI service is temporarily unavailable. The party's actions have been recorded. " +
                "The DM will respond when service is restored. Please try again in a moment.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }

    @SuppressWarnings("unused")
    private String fallbackCheckResolution(UUID sessionId, UUID playerId, String action,
                                           String ability, String skill, int dc, int total,
                                           boolean success, Consumer<String> onChunk,
                                           Throwable throwable) {
        log.error("AI check resolution unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = (success ? "Success! " : "Failure. ")
                + "The " + ability + " check totalled " + total + " against DC " + dc + ". "
                + "[The DM will narrate the consequence when service is restored.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }

    @SuppressWarnings("unused")
    private String fallbackBatchedCheckResolution(UUID sessionId, String checksSummary,
                                                  Consumer<String> onChunk, Throwable throwable) {
        log.error("AI batched check resolution unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = "The dust settles on the party's efforts:\n"
                + (checksSummary == null ? "" : checksSummary)
                + "\n[The DM will narrate the consequences when service is restored.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }

    @SuppressWarnings("unused")
    private String fallbackGroupCheckResolution(UUID sessionId, String checksSummary, int successes,
                                                int total, boolean groupSucceeded,
                                                Consumer<String> onChunk, Throwable throwable) {
        log.error("AI group check resolution unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = "The party's combined effort resolves — " + successes + " of " + total
                + " succeeded, so the group " + (groupSucceeded ? "SUCCEEDS" : "FAILS") + ".\n"
                + (checksSummary == null ? "" : checksSummary)
                + "\n[The DM will narrate the consequences when service is restored.]";
        if (onChunk != null) {
            onChunk.accept(message);
        }
        return message;
    }

    @SuppressWarnings("unused")
    private String fallbackContestResolution(UUID sessionId, String actorName, int actorTotal,
                                             String targetLabel, int targetTotal, boolean actorWon,
                                             Consumer<String> onChunk, Throwable throwable) {
        log.error("AI contest resolution unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        String message = (actorWon ? actorName + " prevails! " : targetLabel + " prevails. ")
                + "The contest scored " + actorTotal + " against " + targetTotal + ". "
                + "[The DM will narrate the consequence when service is restored.]";
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
