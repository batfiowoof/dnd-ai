package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.enums.SpellEffectType;
import com.dungeon.master.model.enums.SpellResolution;
import com.dungeon.master.model.enums.SpellTargetType;
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

/**
 * Loads the machine-readable combat mechanics for every SRD 5.2.1 spell from the
 * structured dataset ({@code resources/dnd5e/srd-5.2.1-structured.json}, the
 * {@code combat} block on each spell) into memory once at startup. Immutable
 * reference data — no DB table. Consumed by {@code CombatService.playerCastSpell}
 * to resolve a cast and by the {@code /api/combat/spells} endpoint so the client
 * knows each spell's target type.
 *
 * <p>See {@code NOTICE} — content derived from the SRD 5.2.1 under CC-BY-4.0.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpellCatalog {

    /** Lightweight view for the client spell menu (filtered to a player's known spells). */
    public record SpellSummary(
            String name, int level, String school,
            SpellEffectType effectType, SpellTargetType targetType,
            Integer maxTargets, boolean concentration, String range, boolean parsed,
            String aoeShape, int aoeSize, String castingTime) {}

    private static final String RESOURCE = "dnd5e/srd-5.2.1-structured.json";

    // Self-instantiated Jackson 2 mapper (Spring Boot 4's bean is Jackson 3) — see SrdContent.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Keyed by lowercased spell name. */
    private final Map<String, SpellEffect> byName = new LinkedHashMap<>();
    private final List<SpellSummary> summaries = new ArrayList<>();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode spells = root == null ? null : root.get("spells");
            if (spells == null || !spells.isArray()) {
                log.warn("Structured spell dataset {} has no spells array — no spell effects loaded", RESOURCE);
                return;
            }
            for (JsonNode sp : spells) {
                String name = text(sp, "name");
                if (name.isBlank()) continue;
                int level = sp.path("level").asInt(-1);
                String school = text(sp, "school");
                String range = text(sp, "range");
                String castingTime = text(sp, "castingTime");
                JsonNode c = sp.get("combat");
                if (c == null || c.isNull()) continue;

                JsonNode scaling = c.path("scaling");
                JsonNode aoe = c.get("aoe");
                SpellEffect effect = new SpellEffect(
                        name,
                        level,
                        enumOr(c, "effectType", SpellEffectType.class, SpellEffectType.UTILITY),
                        enumOr(c, "targetType", SpellTargetType.class, SpellTargetType.ANY),
                        enumOr(c, "resolution", SpellResolution.class, SpellResolution.AUTO),
                        nullable(c, "saveAbility"),
                        nullable(c, "damageDice"),
                        nullable(c, "damageType"),
                        nullable(c, "healDice"),
                        c.path("addCastingMod").asBoolean(false),
                        c.path("halfOnSave").asBoolean(false),
                        scaling.isMissingNode() ? null : nullable(scaling, "perSlotAbove"),
                        scaling.isMissingNode() ? null : nullable(scaling, "cantripDie"),
                        aoe == null || aoe.isNull() ? null : nullable(aoe, "shape"),
                        aoe == null || aoe.isNull() ? 0 : aoe.path("size").asInt(0),
                        (c.hasNonNull("maxTargets")) ? c.get("maxTargets").asInt() : null,
                        c.path("projectiles").asInt(1),
                        nullable(c, "condition"),
                        sp.path("concentration").asBoolean(false),
                        c.path("parsed").asBoolean(false),
                        castingTime,
                        range);

                byName.put(name.toLowerCase(Locale.ROOT), effect);
                summaries.add(new SpellSummary(name, level, school, effect.effectType(),
                        effect.targetType(), effect.maxTargets(), effect.concentration(),
                        range, effect.parsed(), effect.aoeShape(), effect.aoeSize(),
                        effect.castingTime()));
            }
            log.info("Loaded {} SRD 5.2.1 spell effects from {}", byName.size(), RESOURCE);
        } catch (Exception e) {
            log.warn("Failed to load spell effects from {} — combat spellcasting limited: {}",
                    RESOURCE, e.getMessage());
        }
    }

    /* ── public API ──────────────────────────────────────────────── */

    /** Combat mechanics for a spell by (case-insensitive) name. */
    public Optional<SpellEffect> effect(String spellName) {
        if (spellName == null) return Optional.empty();
        return Optional.ofNullable(byName.get(spellName.toLowerCase(Locale.ROOT)));
    }

    /** All spell summaries (the client filters to the player's known spells). */
    public List<SpellSummary> summaries() {
        return List.copyOf(summaries);
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /* ── helpers ─────────────────────────────────────────────────── */

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static <E extends Enum<E>> E enumOr(JsonNode node, String field, Class<E> type, E fallback) {
        String s = nullable(node, field);
        if (s == null) return fallback;
        try {
            return Enum.valueOf(type, s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
