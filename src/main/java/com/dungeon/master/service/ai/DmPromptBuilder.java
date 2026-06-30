package com.dungeon.master.service.ai;

import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CheckModifierService;
import com.dungeon.master.service.game.MonsterCatalog;
import com.dungeon.master.service.game.PlayerStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the user-message and tool-context blocks the DM model consumes: session directives,
 * the party-situation snapshot, the per-turn acting block, monster lore, and the dice-tool context.
 * Pure read/format work — no LLM calls and no game-state mutation — extracted from
 * {@link DmAiService} so the circuit-breaker methods there stay focused on the streaming flow.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmPromptBuilder {

    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final GameSessionRepository sessionRepository;
    private final EnemyRepository enemyRepository;
    private final CombatEncounterRepository encounterRepository;
    private final PlayerStateService playerStateService;
    private final SrdContent srdContent;
    private final MonsterCatalog monsterCatalog;

    /** Build the acting block: a single action reads naturally; a round lists every contributor. */
    public String buildTurnUserMessage(List<Contribution> actions, String context) {
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
    public Map<String, Object> buildToolContext(UUID sessionId, GameSession session,
                                                 Map<UUID, Boolean> spendInspiration) {
        Map<String, UUID> nameToPlayer = new HashMap<>();
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER) {
                continue;
            }
            putName(nameToPlayer, p.getCharacterName(), p.getId());
            putName(nameToPlayer, p.getUsername(), p.getId());
        }
        Difficulty diff = session == null ? null : session.getDifficulty();
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

    public String buildUserMessage(String context, String characterBlock,
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
    public String sessionDirectives(UUID sessionId) {
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
        List<Milestone> openMilestones = session.getMilestones().stream()
                .filter(m -> !m.completed())
                .toList();
        if (!openMilestones.isEmpty()) {
            b.append("- Campaign milestones: these authored story beats are the ONLY way the party gains ")
                    .append("levels — never level them any other way, and never invent a milestone. When the ")
                    .append("party GENUINELY achieves one, call the awardMilestone tool with its exact key ")
                    .append("(the engine advances the whole party a level), then narrate it. Remaining:\n");
            for (Milestone m : openMilestones) {
                b.append("    • key=\"").append(m.key()).append("\" — ").append(m.title());
                if (m.description() != null && !m.description().isBlank()) {
                    b.append(": ").append(m.description());
                }
                b.append("\n");
            }
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
    public String monsterLoreBlock(List<Enemy> enemies) {
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
    public String partySituation(UUID sessionId) {
        return partySituation(sessionId, enemyRepository.findBySessionId(sessionId));
    }

    /**
     * A compact cast list (character name + class/level) for the opening scene, where runtime
     * state may not be seeded yet — so it reads only the Player/Character roster, not HP/conditions.
     */
    public String partyRoster(UUID sessionId) {
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
    public String partySituation(UUID sessionId, List<Enemy> enemies) {
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
        Optional<CombatEncounter> activeEnc = encounterRepository
                .findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE);
        if (activeEnc.isPresent()) {
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
            // A brief grid snapshot so narration matches the tactical map (who is where, how far).
            GridState grid = activeEnc.get().getGridState();
            if (grid != null && grid.getTokens() != null && !grid.getTokens().isEmpty()) {
                String positions = buildPositionsLine(players, livingEnemies, grid);
                if (!positions.isBlank()) {
                    b.append("Positions — ").append(positions).append("\n");
                }
            }
        }

        b.append("---\n\n");
        return b.toString();
    }

    /**
     * Build the short "Positions" line: each player and living enemy with a token, given as a
     * grid cell (e.g. "B3"), with each enemy annotated by its distance to the nearest hero. Kept
     * compact — it never dumps the whole grid.
     */
    private String buildPositionsLine(List<Player> players, List<Enemy> enemies, GridState grid) {
        Map<String, Token> tokens = grid.getTokens();
        List<String> parts = new ArrayList<>();
        for (Player p : players) {
            Token t = tokens.get(p.getId().toString());
            if (t != null) {
                parts.add(partyMemberName(p) + ": " + cellLabel(t.getX(), t.getY()));
            }
        }
        for (Enemy e : enemies) {
            Token t = tokens.get(e.getId().toString());
            if (t == null) {
                continue;
            }
            String part = e.getName() + ": " + cellLabel(t.getX(), t.getY());
            int best = Integer.MAX_VALUE;
            String nearest = null;
            for (Player p : players) {
                Token pt = tokens.get(p.getId().toString());
                if (pt == null) {
                    continue;
                }
                int d = chebyshevFeet(t, pt);
                if (d < best) {
                    best = d;
                    nearest = partyMemberName(p);
                }
            }
            if (nearest != null) {
                part += " (" + best + " ft from " + nearest + ")";
            }
            parts.add(part);
        }
        return String.join("; ", parts);
    }

    /** A spreadsheet-style cell label (column letter + 1-based row), e.g. (1,2) → "B3". */
    private static String cellLabel(int x, int y) {
        String col = (x >= 0 && x < 26) ? String.valueOf((char) ('A' + x)) : String.valueOf(x);
        return col + (y + 1);
    }

    /** 5e Chebyshev grid distance in feet between two tokens (diagonals count as 5 ft). */
    private static int chebyshevFeet(Token a, Token b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())) * 5;
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
    public String characterContext(UUID playerId) {
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
}
