package com.dungeon.master.model.enums;

import java.util.Locale;

/**
 * The 2024 PHB weapon-mastery properties. Every weapon in {@code srd-5.2.1-structured.json} carries a
 * {@code mastery} field naming one of these; a martial who wields the weapon gets its effect when the
 * attack lands (Graze fires on a miss). {@link #fromSrd(String)} parses the bundled string.
 */
public enum WeaponMastery {
    CLEAVE,
    GRAZE,
    NICK,
    PUSH,
    SAP,
    SLOW,
    TOPPLE,
    VEX;

    /** Parse an SRD mastery string ("Topple", "topple") to a value, or {@code null} when unrecognized. */
    public static WeaponMastery fromSrd(String mastery) {
        if (mastery == null || mastery.isBlank()) {
            return null;
        }
        try {
            return WeaponMastery.valueOf(mastery.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
