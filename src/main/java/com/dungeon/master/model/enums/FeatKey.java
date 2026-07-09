package com.dungeon.master.model.enums;

import java.util.Locale;

/**
 * The SRD 5.2.1 origin feats the engine recognises. A character's origin feat is derived from their
 * chosen background (each 2024 background grants exactly one), so this closed set is the bridge from
 * the free-text feat name on a background record to the mechanical hooks in {@code FeatEffects}.
 *
 * <p>{@link #OTHER} covers every feat whose name resolves but carries no wired mechanical effect
 * (Magic Initiate, fighting styles, epic boons, …) — they stay descriptive.
 */
public enum FeatKey {
    ALERT,
    TOUGH,
    LUCKY,
    SAVAGE_ATTACKER,
    SKILLED,
    OTHER;

    /**
     * Resolve a feat's display name (as stored on a background's {@code feat} field, e.g.
     * {@code "Magic Initiate (Cleric)"} or {@code "Alert"}) to a key. The leading words before any
     * parenthetical qualifier are matched case-insensitively; an unknown name maps to {@link #OTHER}.
     */
    public static FeatKey fromName(String featName) {
        if (featName == null || featName.isBlank()) {
            return OTHER;
        }
        String base = featName.trim().toLowerCase(Locale.ROOT);
        int paren = base.indexOf('(');
        if (paren > 0) {
            base = base.substring(0, paren).trim();
        }
        return switch (base) {
            case "alert" -> ALERT;
            case "tough" -> TOUGH;
            case "lucky" -> LUCKY;
            case "savage attacker" -> SAVAGE_ATTACKER;
            case "skilled" -> SKILLED;
            default -> OTHER;
        };
    }
}
