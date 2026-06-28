package com.dungeon.master.model.enums;

/**
 * Kinds of special terrain a grid square may hold. Absence of a {@code TerrainCell}
 * means plain, open floor (costs 5 ft to enter, no cover).
 *
 * <ul>
 *   <li>{@code WALL} — impassable; blocks movement and line of sight (grants cover).</li>
 *   <li>{@code DIFFICULT} — passable but costs double (10 ft) to enter.</li>
 *   <li>{@code HAZARD} — passable, flagged for later phases (damaging terrain); no
 *       movement penalty in Phase A.</li>
 * </ul>
 */
public enum TerrainType {
    WALL,
    DIFFICULT,
    HAZARD
}
