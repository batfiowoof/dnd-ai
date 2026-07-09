package com.dungeon.master.model.enums;

import java.util.Locale;

/**
 * A player's answer to a post-roll reroll prompt: spend Heroic Inspiration, spend a Lucky point, or
 * keep the original roll. {@link #KEEP} is also the auto-answer when the decision window elapses.
 *
 * <p>Keep-semantics differ by resource (2024 rules): Inspiration <em>must</em> use the new roll,
 * while Lucky lets you keep the better of the two — {@link CombatService}/{@code DmRollTools} apply
 * this when resolving the reroll.
 */
public enum RerollResource {
    INSPIRATION,
    LUCK,
    KEEP;

    public static RerollResource parse(String raw) {
        if (raw == null) {
            return KEEP;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "INSPIRATION" -> INSPIRATION;
            case "LUCK" -> LUCK;
            default -> KEEP;
        };
    }
}
