package com.dungeon.master.model.dto;

/**
 * Maps an exact HP value to a coarse 5E-style health band so clients can show a creature's
 * condition (per the "you don't see a monster's exact HP" convention) without leaking the real
 * number. The server and the LLM/DM keep exact HP; only client-facing DTOs carry the band.
 */
public final class HealthBand {

    private HealthBand() {}

    public static final String HEALTHY = "healthy";   // > 75%
    public static final String HURT = "hurt";         // > 50%
    public static final String BLOODIED = "bloodied"; // > 25%
    public static final String CRITICAL = "critical"; // > 0%
    public static final String DOWN = "down";         // <= 0

    /** Band for {@code current}/{@code max} HP. Non-positive current → DOWN; non-positive max → HEALTHY. */
    public static String of(int current, int max) {
        if (current <= 0) {
            return DOWN;
        }
        if (max <= 0) {
            return HEALTHY;
        }
        double pct = (double) current / max;
        if (pct > 0.75) return HEALTHY;
        if (pct > 0.50) return HURT;
        if (pct > 0.25) return BLOODIED;
        return CRITICAL;
    }
}
