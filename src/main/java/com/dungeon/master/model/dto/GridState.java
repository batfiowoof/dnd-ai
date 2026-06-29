package com.dungeon.master.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The spatial state of a combat encounter: a {@code width × height} square grid
 * (each square = 5 ft) plus terrain, decorative features, and combatant tokens.
 * Persisted as JSONB on {@code CombatEncounter} and sent to clients in the combat
 * snapshot. Mutable — later phases move tokens and edit terrain in place.
 *
 * <p>{@code tokens} is keyed by combatant refId (the player/enemy UUID as a String),
 * matching the {@code refId} used in the initiative order.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GridState {

    /** Number of columns (x ranges 0..width-1). */
    private int width;

    /** Number of rows (y ranges 0..height-1). */
    private int height;

    /** Optional background map image URL; null for a plain arena. */
    private String backgroundImageUrl;

    /** Special terrain squares (walls, difficult, hazard). Empty = fully open. */
    @Builder.Default
    private List<TerrainCell> terrain = new ArrayList<>();

    /** Decorative labelled points of interest. */
    @Builder.Default
    private List<MapFeature> features = new ArrayList<>();

    /** Combatant tokens keyed by refId (player/enemy UUID string). */
    @Builder.Default
    private Map<String, Token> tokens = new LinkedHashMap<>();

    /**
     * Active spell-created terrain effects (e.g. Entangle's difficult terrain). Bookkeeping for
     * the lifecycle — the cells themselves live in {@link #terrain}; this lets the engine remove
     * exactly a spell's cells when it ends. Empty when no terrain spell is active.
     */
    @Builder.Default
    private List<TerrainZone> zones = new ArrayList<>();
}
