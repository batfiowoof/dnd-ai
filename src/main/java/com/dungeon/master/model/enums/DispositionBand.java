package com.dungeon.master.model.enums;

/**
 * How an NPC feels about the party, bucketed from a signed disposition score in [-100, 100]. The
 * score is the authoritative runtime value the DM nudges by deltas; the band is derived for display
 * and for the DM's tone/behaviour guidance. Single source of truth for the score↔band mapping —
 * mirrored on the frontend in {@code lib/dispositions.ts}.
 */
public enum DispositionBand {

    HOSTILE("Hostile", -100, -45, -70),
    UNFRIENDLY("Unfriendly", -44, -15, -30),
    NEUTRAL("Neutral", -14, 14, 0),
    FRIENDLY("Friendly", 15, 44, 30),
    DEVOTED("Devoted", 45, 100, 70);

    /** Lowest and highest disposition score a party attitude can reach. */
    public static final int MIN_SCORE = -100;
    public static final int MAX_SCORE = 100;

    private final String label;
    private final int min;
    private final int max;
    private final int center;

    DispositionBand(String label, int min, int max, int center) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.center = center;
    }

    public String label() {
        return label;
    }

    /** A representative score for this band — used when converting an authored band back to a score. */
    public int center() {
        return center;
    }

    /** Clamp any score into the valid [-100, 100] range. */
    public static int clamp(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    /** The band a (clamped) disposition score falls into. */
    public static DispositionBand fromScore(int score) {
        int s = clamp(score);
        for (DispositionBand band : values()) {
            if (s >= band.min && s <= band.max) {
                return band;
            }
        }
        return NEUTRAL; // unreachable — the bands tile the whole range
    }
}
