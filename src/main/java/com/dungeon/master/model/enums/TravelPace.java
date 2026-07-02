package com.dungeon.master.model.enums;

/**
 * D&D 5e overland travel pace. The per-day distances assume an 8-hour travel day.
 * A faster pace covers more ground but is easier to ambush; a slower pace is more cautious.
 *
 * <ul>
 *   <li>{@link #FAST} — 30 miles/day; harder to spot danger (encounter chance raised).</li>
 *   <li>{@link #NORMAL} — 24 miles/day; the default.</li>
 *   <li>{@link #SLOW} — 18 miles/day; wary and quiet (encounter chance lowered).</li>
 * </ul>
 */
public enum TravelPace {

    FAST(30, 1.5, "a fast"),
    NORMAL(24, 1.0, "a steady"),
    SLOW(18, 0.6, "a cautious");

    private final int milesPerDay;
    private final double encounterMultiplier;
    private final String label;

    TravelPace(int milesPerDay, double encounterMultiplier, String label) {
        this.milesPerDay = milesPerDay;
        this.encounterMultiplier = encounterMultiplier;
        this.label = label;
    }

    /** Miles the party covers in one 8-hour travel day at this pace. */
    public int milesPerDay() {
        return milesPerDay;
    }

    /** Multiplier applied to the base per-leg encounter chance for this pace. */
    public double encounterMultiplier() {
        return encounterMultiplier;
    }

    /** Prose label for narration, e.g. "a steady" → "a steady pace". */
    public String label() {
        return label;
    }
}
