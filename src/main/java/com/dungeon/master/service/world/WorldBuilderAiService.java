package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldGenerateRequest;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldOverviewSuggestion;
import com.dungeon.master.model.dto.WorldRegion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Per-section AI assistance for the World Builder. Each method asks the chat model to return strict
 * JSON for one section, grounded in the current draft for coherence, and maps it onto the section
 * DTOs the wizard already edits. Generated content is run through {@link WorldSanitizer} so it is
 * clean and (for monsters) combat-legal before it reaches the client. Any model failure surfaces as a
 * friendly {@link IllegalStateException} (→ 400 with a readable message), never a raw stack trace.
 *
 * <p>Uses a fresh {@link ChatClient} over the shared {@link ChatModel} with a worldbuilding system
 * persona, so it doesn't inherit the DM-narrator voice (mirrors {@code SceneGenerator}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorldBuilderAiService {

    private static final String SYSTEM =
            "You are a Dungeons & Dragons 5E worldbuilding assistant. You help a player author a "
                    + "campaign setting. Be evocative but concise, follow the requested JSON shape "
                    + "EXACTLY, and never include commentary outside the JSON.";

    private static final String FRIENDLY_FAIL =
            "The AI couldn't generate that just now. Please try again in a moment.";

    private final ChatModel chatModel;
    private final WorldSanitizer sanitizer;

    public WorldOverviewSuggestion generateOverview(WorldGenerateRequest req) {
        String user = """
                Draft a D&D campaign world overview.
                %s
                Return JSON: tagline (a one-line hook), tone (e.g. Heroic, Grimdark, Mystery),
                magicLevel (Low, Standard, or High), and overview (2-4 short paragraphs of markdown
                covering the setting, its defining truths, and the ONE central conflict driving the
                campaign). Keep it a tight one-pager.""".formatted(context(req));
        return call(user, WorldOverviewSuggestion.class);
    }

    public List<WorldRegion> suggestRegions(WorldGenerateRequest req) {
        String user = """
                Propose 5 to 7 notable regions for this world — a mix like a home base, a rival's
                stronghold, a mystery site, a wild zone, and neutral ground.
                %s
                Return a JSON array of objects: name, type (e.g. City, Ruin, Wilds), description
                (one or two sentences on what's there and why adventurers care).""".formatted(context(req));
        return sanitizer.cleanRegions(callList(user, new ParameterizedTypeReference<List<WorldRegion>>() {}));
    }

    public List<WorldFaction> suggestFactions(WorldGenerateRequest req) {
        String user = """
                Propose 3 to 5 factions for this world. Each must be a "living magnet" that pulls the
                party toward the next development.
                %s
                Return a JSON array of objects: name, goal (what it wants), resource (what gives it
                power), pressure (why it must act now), description (extra flavour).""".formatted(context(req));
        return sanitizer.cleanFactions(callList(user, new ParameterizedTypeReference<List<WorldFaction>>() {}));
    }

    public List<WorldNpc> suggestNpcs(WorldGenerateRequest req) {
        String user = """
                Propose 4 to 6 key NPCs for this world. Each should sit somewhere in the world and,
                ideally, have a bond that ties them to the party or the central conflict.
                %s
                Return a JSON array of objects: name, race, role, location, bond, description.""".formatted(context(req));
        return sanitizer.cleanNpcs(callList(user, new ParameterizedTypeReference<List<WorldNpc>>() {}));
    }

    public CustomMonster suggestMonster(WorldGenerateRequest req) {
        String user = """
                Design ONE homebrew D&D 5E monster that fits this world. It must be combat-ready.
                %s
                Return a JSON object: name, size (Tiny/Small/Medium/Large/Huge/Gargantuan), type
                (e.g. Undead, Beast), cr (challenge rating as a number), ac (armor class), hp (average
                hit points), hpDice (e.g. "5d8+5"), speed (feet), dexMod (its DEX modifier as a
                number), abilities (an object with integer STR, DEX, CON, INT, WIS, CHA), attacks (a
                JSON array of objects: name, kind ("MELEE" or "RANGED"), toHit (number), reach (feet
                or null), range (feet or null), damageDice (e.g. "2d6+2"), damageType), and multiattack
                (either null, or an object with count (number) and attack (the name of the repeated
                attack)). Give it at least one attack.""".formatted(context(req));
        CustomMonster raw = call(user, CustomMonster.class);
        // Guarantee a clean, combat-legal, namespaced block (drops it if the model omitted essentials).
        List<CustomMonster> cleaned = sanitizer.sanitizeMonsters(raw == null ? List.of() : List.of(raw));
        if (cleaned.isEmpty()) {
            throw new IllegalStateException(
                    "The AI's monster was missing required stats (AC, HP, or an attack). Please try again.");
        }
        return cleaned.get(0);
    }

    public List<Milestone> suggestMilestones(WorldGenerateRequest req) {
        String user = """
                Propose campaign milestones — the party's leveling gates — for this world. Think in
                three acts (a hook, rising complications, a finale) with a few beats each.
                %s
                Return a JSON array of objects: key (a short stable kebab-case id), title (display
                name), description (the beat that earns the level-up).""".formatted(context(req));
        return sanitizer.normalizeMilestones(
                callList(user, new ParameterizedTypeReference<List<Milestone>>() {}));
    }

    /* ── internals ───────────────────────────────────────────────── */

    /** Render the current draft as grounding context for the model. */
    private static String context(WorldGenerateRequest req) {
        if (req == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("Current draft:\n");
        appendIf(b, "World name", req.name());
        appendIf(b, "Tone", req.tone());
        appendIf(b, "Magic level", req.magicLevel());
        appendIf(b, "Overview", trim(req.overview(), 1200));
        appendIf(b, "Extra instruction", req.instruction());
        return b.toString();
    }

    private static void appendIf(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) {
            b.append(label).append(": ").append(value.trim()).append("\n");
        }
    }

    private <T> T call(String user, Class<T> type) {
        try {
            return ChatClient.create(chatModel).prompt()
                    .system(SYSTEM)
                    .user(user)
                    .call()
                    .entity(type);
        } catch (Exception e) {
            log.warn("World builder AI generation failed: {}", e.getMessage());
            throw new IllegalStateException(FRIENDLY_FAIL);
        }
    }

    private <T> T callList(String user, ParameterizedTypeReference<T> type) {
        try {
            return ChatClient.create(chatModel).prompt()
                    .system(SYSTEM)
                    .user(user)
                    .call()
                    .entity(type);
        } catch (Exception e) {
            log.warn("World builder AI generation failed: {}", e.getMessage());
            throw new IllegalStateException(FRIENDLY_FAIL);
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
