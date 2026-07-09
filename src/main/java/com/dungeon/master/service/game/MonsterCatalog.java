package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.MonsterAction;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.enums.MonsterActionKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the SRD 5.2.1 monster stat blocks from {@code resources/dnd5e/monsters.json}
 * (produced by {@code tools/extract-srd.py}) into memory once at startup. Replaces
 * the old hardcoded {@code Bestiary}: provides full combat stats (AC/HP/attacks/
 * multiattack/CR) for {@code CombatService} to instantiate per-encounter enemies,
 * the valid key set for the {@code [[ENCOUNTER:…]]} parser + DM prompt, and a
 * summary list for the host's encounter picker.
 *
 * <p>Unlike {@code SrdContent} (stat-free lore for narration), this file keeps the
 * numbers — it is the engine's authoritative monster math.
 *
 * <p>See {@code NOTICE} — content derived from the SRD 5.2.1 under CC-BY-4.0.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonsterCatalog {

    /**
     * Lightweight view for the host's encounter picker (sorted by CR). {@code hasLair} lets the
     * picker offer the "In its lair" toggle only for monsters that have authored lair actions.
     */
    public record MonsterSummary(String key, String name, Double cr, String type,
                                 String size, Integer hp, Integer ac, boolean hasLair) {}

    private static final String RESOURCE = "dnd5e/monsters.json";

    /**
     * Hand-authored boss mechanics merged onto the generated stat blocks by key — the same curated
     * overlay pattern as {@code MagicItemCatalog}'s {@code magic-item-effects.json}.
     */
    private static final String ACTIONS_RESOURCE = "dnd5e/monster-actions.json";

    // Self-instantiated Jackson 2 mapper (Spring Boot 4's bean is Jackson 3) — see SrdContent.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Keyed by uppercased monster key (e.g. "GOBLIN_WARRIOR"). */
    private final Map<String, MonsterTemplate> byKey = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        Map<String, JsonNode> actions = loadActionOverlay();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode arr = objectMapper.readTree(in);
            if (arr == null || !arr.isArray()) {
                log.warn("Monster dataset {} is not a JSON array — no monsters loaded", RESOURCE);
                return;
            }
            for (JsonNode node : arr) {
                MonsterTemplate t = parse(node, actions);
                // require enough to fight with: a key, AC, HP, and at least one attack
                if (t == null || t.ac() == null || t.hp() == null || t.attacks().isEmpty()) {
                    continue;
                }
                byKey.put(t.key().toUpperCase(Locale.ROOT), t);
            }
            long legendary = byKey.values().stream().filter(MonsterTemplate::isLegendary).count();
            log.info("Loaded {} SRD 5.2.1 monster stat blocks from {} ({} legendary)",
                    byKey.size(), RESOURCE, legendary);
        } catch (Exception e) {
            log.warn("Failed to load monsters from {} — combat falls back to Bestiary: {}",
                    RESOURCE, e.getMessage());
        }
    }

    /**
     * The curated legendary/lair overlay, keyed by uppercased monster key. A missing or malformed
     * file only costs the bosses their legendary actions, so it degrades to a warning.
     */
    private Map<String, JsonNode> loadActionOverlay() {
        Map<String, JsonNode> overlay = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(ACTIONS_RESOURCE).getInputStream()) {
            JsonNode monsters = objectMapper.readTree(in).path("monsters");
            if (monsters.isObject()) {
                monsters.fields().forEachRemaining(
                        e -> overlay.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to load legendary/lair overlay {} — bosses lose legendary actions: {}",
                    ACTIONS_RESOURCE, e.getMessage());
        }
        return overlay;
    }

    /* ── public API ──────────────────────────────────────────────── */

    public Optional<MonsterTemplate> get(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(byKey.get(key.toUpperCase(Locale.ROOT)));
    }

    /** Valid encounter keys (reused by the DM {@code [[ENCOUNTER:…]]} parser for validation). */
    public Set<String> keys() {
        return Set.copyOf(byKey.keySet());
    }

    /**
     * A curated, level-appropriate slice of keys (lowest CR first) for the DM prompt, so the
     * prompt isn't bloated with the entire bestiary. The {@code [[ENCOUNTER:…]]} parser still
     * validates against the full {@link #keys()} set, so the DM may name any catalog monster.
     */
    public List<String> promptKeys(int limit) {
        return summaries().stream()
                .map(MonsterSummary::key)
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Summaries sorted by challenge rating then name, for the host's encounter picker. */
    public List<MonsterSummary> summaries() {
        List<MonsterSummary> out = new ArrayList<>();
        for (MonsterTemplate t : byKey.values()) {
            out.add(new MonsterSummary(t.key(), t.name(), t.cr(), t.type(), t.size(),
                    t.hp(), t.ac(), t.hasLair()));
        }
        out.sort((a, b) -> {
            double ca = a.cr() == null ? 99 : a.cr();
            double cb = b.cr() == null ? 99 : b.cr();
            int c = Double.compare(ca, cb);
            return c != 0 ? c : a.name().compareToIgnoreCase(b.name());
        });
        return out;
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    /* ── parsing ─────────────────────────────────────────────────── */

    private MonsterTemplate parse(JsonNode n, Map<String, JsonNode> actionOverlay) {
        String key = text(n, "key");
        String name = text(n, "name");
        if (key.isBlank() || name.isBlank()) return null;

        Map<String, Integer> abilities = new LinkedHashMap<>();
        JsonNode ab = n.get("abilities");
        if (ab != null && ab.isObject()) {
            ab.fields().forEachRemaining(e -> abilities.put(e.getKey(), e.getValue().asInt(10)));
        }

        List<MonsterAttack> attacks = new ArrayList<>();
        JsonNode atks = n.get("attacks");
        if (atks != null && atks.isArray()) {
            for (JsonNode a : atks) {
                attacks.add(new MonsterAttack(
                        text(a, "name"),
                        a.path("kind").asText("MELEE"),
                        a.path("toHit").asInt(0),
                        a.hasNonNull("reach") ? a.get("reach").asInt() : null,
                        a.hasNonNull("range") ? a.get("range").asInt() : null,
                        text(a, "damageDice"),
                        text(a, "damageType")));
            }
        }

        MonsterTemplate.Multiattack multi = null;
        JsonNode m = n.get("multiattack");
        if (m != null && m.isObject() && m.hasNonNull("count")) {
            multi = new MonsterTemplate.Multiattack(m.get("count").asInt(1),
                    m.path("attack").asText(null));
        }

        // Boss mechanics live only in the curated overlay — absent for the other ~297 monsters.
        JsonNode boss = actionOverlay.get(key.toUpperCase(Locale.ROOT));
        List<MonsterAction> legendary = parseActions(boss == null ? null : boss.get("legendaryActions"));
        List<MonsterAction> lair = parseActions(boss == null ? null : boss.get("lairActions"));
        int legendaryMax = boss == null ? 0 : boss.path("legendaryActionMax").asInt(0);
        int legendaryResistances = boss == null ? 0 : boss.path("legendaryResistances").asInt(0);

        return new MonsterTemplate(
                key, name,
                nullable(n, "size"), nullable(n, "type"),
                n.hasNonNull("cr") ? n.get("cr").asDouble() : null,
                n.hasNonNull("ac") ? n.get("ac").asInt() : null,
                n.hasNonNull("hp") ? n.get("hp").asInt() : null,
                nullable(n, "hpDice"),
                n.hasNonNull("speed") ? n.get("speed").asInt() : null,
                n.path("dexMod").asInt(0),
                abilities, attacks, multi,
                legendaryMax, legendary, lair, legendaryResistances);
    }

    /** Parse a legendary/lair action array from the overlay; unknown kinds are dropped. */
    private List<MonsterAction> parseActions(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<MonsterAction> out = new ArrayList<>();
        for (JsonNode a : arr) {
            MonsterActionKind kind;
            try {
                kind = MonsterActionKind.valueOf(a.path("kind").asText("").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping monster action {} with unknown kind {}",
                        text(a, "name"), a.path("kind").asText());
                continue;
            }
            out.add(new MonsterAction(
                    text(a, "name"),
                    a.path("cost").asInt(1),
                    kind,
                    nullable(a, "attackName"),
                    nullable(a, "saveAbility"),
                    a.hasNonNull("saveDc") ? a.get("saveDc").asInt() : null,
                    nullable(a, "damageDice"),
                    nullable(a, "damageType"),
                    a.path("halfOnSave").asBoolean(false),
                    nullable(a, "condition"),
                    a.hasNonNull("conditionRounds") ? a.get("conditionRounds").asInt() : null,
                    a.hasNonNull("radiusFeet") ? a.get("radiusFeet").asInt() : null,
                    nullable(a, "description")));
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static String nullable(JsonNode node, String field) {
        String s = text(node, field);
        return s.isEmpty() ? null : s;
    }
}
