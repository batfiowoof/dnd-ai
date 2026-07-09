package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.MagicItemEffect;
import com.dungeon.master.model.enums.EquipSlot;
import com.dungeon.master.model.enums.MagicItemRarity;
import com.dungeon.master.service.ai.SrdContent;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mechanical catalog of SRD 5.2.1 magic items. Metadata (item type, best-effort paper-doll
 * slot, rarity, attunement) is parsed from the prose <em>header</em> of every {@code MAGIC_ITEM}
 * entry in {@link SrdContent}; mechanical effects come from a curated overlay
 * ({@code resources/dnd5e/magic-item-effects.json}) merged by slug, or are synthesized from the
 * item's own name ({@code +N} weapons/armor, typed Resistance items). Uncurated, unsynthesizable
 * items load as metadata-only ({@code parsed == false}) and are DM-narrated. Immutable reference
 * data — no DB table. Mirrors the {@code SpellCatalog}/{@code MonsterCatalog} load pattern.
 *
 * <p>See {@code NOTICE} — content derived from the SRD 5.2.1 under CC-BY-4.0.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MagicItemCatalog {

    /** Lightweight view for the client (so it can badge inventory items as magic). */
    public record MagicItemSummary(
            String key, String name, String itemType, EquipSlot slot,
            MagicItemRarity rarity, boolean requiresAttunement, boolean hasEffect) {}

    private static final String OVERLAY_RESOURCE = "dnd5e/magic-item-effects.json";
    private static final String MAGIC_ITEM_TYPE = "MAGIC_ITEM";
    private static final String KEY_PREFIX = "SRD:MAGIC_ITEM:";

    /** Damage types recognized for typed-resistance name synthesis (canonical casing). */
    private static final List<String> DAMAGE_TYPES = List.of(
            "Acid", "Bludgeoning", "Cold", "Fire", "Force", "Lightning", "Necrotic",
            "Piercing", "Poison", "Psychic", "Radiant", "Slashing", "Thunder");

    // Self-instantiated Jackson 2 mapper (Spring Boot 4's bean is Jackson 3) — see SrdContent.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SrdContent srdContent;

    /** Keyed by lowercased item name. */
    private final Map<String, MagicItemEffect> byName = new LinkedHashMap<>();
    private final List<MagicItemSummary> summaries = new ArrayList<>();

    @PostConstruct
    void load() {
        Map<String, JsonNode> overlay = loadOverlay();
        try {
            for (SrdContent.Entry entry : srdContent.all()) {
                if (!MAGIC_ITEM_TYPE.equalsIgnoreCase(entry.type())) continue;
                MagicItemEffect effect = parseEntry(entry, overlay);
                if (effect == null) continue;
                byName.put(effect.name().toLowerCase(Locale.ROOT), effect);
                summaries.add(new MagicItemSummary(effect.key(), effect.name(), effect.itemType(),
                        effect.slot(), effect.rarity(), effect.requiresAttunement(),
                        effect.hasMechanicalEffect()));
            }
            log.info("Loaded {} SRD 5.2.1 magic items ({} with mechanical effects)",
                    byName.size(), byName.values().stream().filter(MagicItemEffect::hasMechanicalEffect).count());
        } catch (Exception e) {
            log.warn("Failed to build magic-item catalog — magic items stay narrative: {}", e.getMessage());
        }
    }

    private Map<String, JsonNode> loadOverlay() {
        Map<String, JsonNode> overlay = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(OVERLAY_RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode effects = root == null ? null : root.get("effects");
            if (effects != null && effects.isObject()) {
                effects.fields().forEachRemaining(e -> overlay.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to load magic-item effect overlay {} — curated effects disabled: {}",
                    OVERLAY_RESOURCE, e.getMessage());
        }
        return overlay;
    }

    /* ── prose-header parsing ────────────────────────────────────────── */

    private MagicItemEffect parseEntry(SrdContent.Entry entry, Map<String, JsonNode> overlay) {
        String name = entry.title();
        if (name == null || name.isBlank()) return null;
        String slug = slugOf(entry.key());

        // The header is the start of the prose, after the leading "<Name>: ". Scan a short window
        // so a rarity/attunement word deep in the description can't be mistaken for the header's.
        String body = stripLeadingName(entry.content(), name);
        String window = body.length() > 220 ? body.substring(0, 220) : body;

        String itemType = parseItemType(window);
        String subtype = parseSubtype(window);
        EquipSlot slot = inferSlot(itemType, subtype, name);
        MagicItemRarity rarity = parseRarity(window);
        boolean attunement = window.toLowerCase(Locale.ROOT).contains("requires attunement");

        // Effect: curated overlay first (keyed by slug), else synthesized from the name.
        JsonNode overlayNode = overlay.get(slug);
        int acBonus = 0, attackBonus = 0, damageBonus = 0, saveBonus = 0;
        boolean requiresNoArmor = false;
        List<String> resistances = new ArrayList<>();
        Map<String, Integer> setAbility = new LinkedHashMap<>();
        List<String> advantageOn = new ArrayList<>();
        if (overlayNode != null) {
            acBonus = overlayNode.path("acBonus").asInt(0);
            attackBonus = overlayNode.path("attackBonus").asInt(0);
            damageBonus = overlayNode.path("damageBonus").asInt(0);
            saveBonus = overlayNode.path("saveBonus").asInt(0);
            requiresNoArmor = overlayNode.path("requiresNoArmor").asBoolean(false);
            readStringArray(overlayNode.get("resistances"), resistances);
            readStringArray(overlayNode.get("advantageOn"), advantageOn);
            JsonNode set = overlayNode.get("setAbility");
            if (set != null && set.isObject()) {
                set.fields().forEachRemaining(e -> setAbility.put(
                        e.getKey().toUpperCase(Locale.ROOT), e.getValue().asInt(0)));
            }
        }

        MagicItemEffect effect = new MagicItemEffect(slug, name, itemType, slot, rarity, attunement,
                acBonus, attackBonus, damageBonus, saveBonus, requiresNoArmor,
                List.copyOf(resistances), Map.copyOf(setAbility), List.copyOf(advantageOn), false);
        // parsed flag reflects whether the engine can resolve anything mechanically.
        return withParsed(effect, effect.hasMechanicalEffect());
    }

    private static MagicItemEffect withParsed(MagicItemEffect e, boolean parsed) {
        return new MagicItemEffect(e.key(), e.name(), e.itemType(), e.slot(), e.rarity(),
                e.requiresAttunement(), e.acBonus(), e.attackBonus(), e.damageBonus(), e.saveBonus(),
                e.requiresNoArmor(), e.resistances(), e.setAbility(), e.advantageOn(), parsed);
    }

    private static String slugOf(String key) {
        if (key == null) return "";
        String k = key.trim();
        String upper = k.toUpperCase(Locale.ROOT);
        return upper.startsWith(KEY_PREFIX) ? k.substring(KEY_PREFIX.length()).toLowerCase(Locale.ROOT)
                : k.toLowerCase(Locale.ROOT);
    }

    private static String stripLeadingName(String content, String name) {
        if (content == null) return "";
        String c = content.trim();
        String prefix = name + ":";
        if (c.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return c.substring(prefix.length()).trim();
        }
        return c;
    }

    private static final Pattern ITEM_TYPE = Pattern.compile("^([A-Za-z][A-Za-z ]*?)\\s*(?:\\(|,)");
    private static final Pattern SUBTYPE = Pattern.compile("^[A-Za-z][A-Za-z ]*?\\(([^)]*)\\)");
    private static final Pattern RARITY = Pattern.compile(
            "(?i)\\b(Rarity Varies|Very Rare|Legendary|Artifact|Uncommon|Common|Rare)\\b");

    private static String parseItemType(String header) {
        Matcher m = ITEM_TYPE.matcher(header);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String parseSubtype(String header) {
        Matcher m = SUBTYPE.matcher(header);
        return m.find() ? m.group(1).trim() : "";
    }

    private static MagicItemRarity parseRarity(String header) {
        Matcher m = RARITY.matcher(header);
        return m.find() ? MagicItemRarity.fromText(m.group(1)) : MagicItemRarity.UNKNOWN;
    }

    private static EquipSlot inferSlot(String itemType, String subtype, String name) {
        String t = itemType == null ? "" : itemType.toLowerCase(Locale.ROOT);
        String sub = subtype == null ? "" : subtype.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (t.startsWith("armor")) return sub.contains("shield") ? EquipSlot.OFF_HAND : EquipSlot.CHEST;
        if (t.startsWith("weapon")) return EquipSlot.MAIN_HAND;
        if (t.equals("ring")) return EquipSlot.RING;
        // Wondrous Item / Rod / Staff / Wand — infer from the name.
        if (containsAny(n, "boots", "slippers", "sandals")) return EquipSlot.FEET;
        if (containsAny(n, "gauntlets", "gloves", "bracers")) return EquipSlot.HANDS;
        if (containsAny(n, "helm", "hat", "headband", "circlet", "crown", "cap", "goggles", "eyes")) return EquipSlot.HEAD;
        if (containsAny(n, "amulet", "necklace", "medallion", "periapt", "brooch", "pendant",
                "scarab", "cloak", "cape", "mantle")) return EquipSlot.NECK;
        if (n.contains("ring")) return EquipSlot.RING;
        return null;
    }

    /* ── public API ──────────────────────────────────────────────────── */

    /**
     * Mechanics for a held item by (case-insensitive) name. Falls back to synthesizing an effect
     * from the name for {@code +N} weapons/armor/shields and typed Resistance items, so DM/loot-
     * granted items work without a curated entry. Empty when the name isn't a (known) magic item.
     */
    public Optional<MagicItemEffect> forItemName(String itemName) {
        if (itemName == null || itemName.isBlank()) return Optional.empty();
        String key = itemName.trim().toLowerCase(Locale.ROOT);
        MagicItemEffect exact = byName.get(key);
        if (exact != null && exact.hasMechanicalEffect()) return Optional.of(exact);
        Optional<MagicItemEffect> synthetic = synthesize(itemName.trim());
        if (synthetic.isPresent()) return synthetic;
        return Optional.ofNullable(exact);
    }

    /** All magic-item summaries (the client badges matching inventory names). */
    public List<MagicItemSummary> summaries() {
        return List.copyOf(summaries);
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /* ── name synthesis ──────────────────────────────────────────────── */

    private static final Pattern PLUS_N = Pattern.compile("\\+\\s*([123])\\b");

    private Optional<MagicItemEffect> synthesize(String name) {
        String n = name.toLowerCase(Locale.ROOT);

        Matcher plus = PLUS_N.matcher(name);
        if (plus.find()) {
            int bonus = Integer.parseInt(plus.group(1));
            if (n.contains("shield")) {
                return Optional.of(synthetic(name, "Armor (Shield)", EquipSlot.OFF_HAND, false,
                        bonus, 0, 0, List.of()));
            }
            if (containsAny(n, "armor", "mail", "plate", "leather", "breastplate", "scale",
                    "splint", "hide", "padded", "chain")) {
                return Optional.of(synthetic(name, "Armor", EquipSlot.CHEST, false,
                        bonus, 0, 0, List.of()));
            }
            // default: a magic weapon (+N to attack and damage).
            return Optional.of(synthetic(name, "Weapon", EquipSlot.MAIN_HAND, false,
                    0, bonus, bonus, List.of()));
        }

        if (n.contains("resist")) {
            for (String type : DAMAGE_TYPES) {
                if (n.contains(type.toLowerCase(Locale.ROOT))) {
                    EquipSlot slot = n.contains("ring") ? EquipSlot.RING
                            : containsAny(n, "cloak", "cape", "mantle", "amulet") ? EquipSlot.NECK
                            : n.contains("armor") ? EquipSlot.CHEST : null;
                    return Optional.of(synthetic(name, "Wondrous Item", slot, true,
                            0, 0, 0, List.of(type)));
                }
            }
        }
        return Optional.empty();
    }

    private static MagicItemEffect synthetic(String name, String itemType, EquipSlot slot,
            boolean attunement, int acBonus, int attackBonus, int damageBonus, List<String> resistances) {
        return new MagicItemEffect(slugFromName(name), name, itemType, slot,
                MagicItemRarity.UNKNOWN, attunement, acBonus, attackBonus, damageBonus, 0,
                false, List.copyOf(resistances), Map.of(), List.of(), true);
    }

    private static String slugFromName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static void readStringArray(JsonNode node, List<String> out) {
        if (node != null && node.isArray()) {
            node.forEach(v -> {
                String s = v.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            });
        }
    }
}
