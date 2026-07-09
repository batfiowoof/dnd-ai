package com.dungeon.master.model.enums;

import java.util.Locale;

/**
 * Magic-item rarity tier, parsed from the SRD prose header (e.g. "…, Rare (Requires Attunement)").
 * {@link #VARIES} covers items whose rarity scales with a chosen variant ("Rarity Varies", e.g.
 * Belt of Giant Strength); {@link #UNKNOWN} is the fail-soft fallback when the header can't be read.
 */
public enum MagicItemRarity {
    COMMON,
    UNCOMMON,
    RARE,
    VERY_RARE,
    LEGENDARY,
    ARTIFACT,
    VARIES,
    UNKNOWN;

    /**
     * Case-insensitive parse of a rarity word from a prose header ("very rare" / "Very Rare" →
     * {@link #VERY_RARE}), tolerant of spaces/hyphens. Returns {@link #UNKNOWN} for anything else.
     */
    public static MagicItemRarity fromText(String raw) {
        if (raw == null) return UNKNOWN;
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (s) {
            case "common" -> COMMON;
            case "uncommon" -> UNCOMMON;
            case "rare" -> RARE;
            case "very_rare" -> VERY_RARE;
            case "legendary" -> LEGENDARY;
            case "artifact" -> ARTIFACT;
            case "rarity_varies", "varies" -> VARIES;
            default -> UNKNOWN;
        };
    }
}
