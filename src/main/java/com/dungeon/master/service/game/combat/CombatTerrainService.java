package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.dto.TerrainCell;
import com.dungeon.master.model.dto.TerrainZone;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.TerrainType;
import com.dungeon.master.service.game.GridService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Manages the difficult-terrain zones that area spells (Entangle, Web, Grease, …) stamp onto
 * the tactical grid. Mutates the passed {@link CombatEncounter}'s grid in place and reports
 * whether anything changed; it never persists or broadcasts — the orchestrator owns the
 * {@code save(...)} and the grid refresh.
 */
@Component
@RequiredArgsConstructor
public class CombatTerrainService {

    private final GridService gridService;

    /**
     * Stamp a terrain spell's area onto the grid for its duration: add a terrain cell for each
     * template square not already terrain, and record the zone so its cells can be cleared when
     * the spell ends (concentration → null expiry; otherwise ~1 minute / 10 rounds). Returns
     * {@code true} when a zone was added (the caller should persist).
     */
    public boolean stampTerrainZone(CombatEncounter enc, SpellEffect effect, Player caster,
                                    Integer originX, Integer originY) {
        GridState grid = enc.getGridState();
        if (grid == null || originX == null || originY == null
                || effect.aoeShape() == null || effect.aoeSize() <= 0) {
            return false;
        }
        TerrainType type;
        try {
            type = TerrainType.valueOf(effect.terrain().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (grid.getZones() == null) {
            grid.setZones(new ArrayList<>());          // legacy encounter saved before the field existed
        }
        List<GridService.Square> cells =
                gridService.aoeCells(originX, originY, effect.aoeShape(), effect.aoeSize());
        List<TerrainCell> owned = new ArrayList<>();
        for (GridService.Square sq : cells) {
            if (sq.x() < 0 || sq.y() < 0 || sq.x() >= grid.getWidth() || sq.y() >= grid.getHeight()) {
                continue;
            }
            boolean hasTerrain = grid.getTerrain().stream()
                    .anyMatch(t -> t.x() == sq.x() && t.y() == sq.y());
            boolean coveredByZone = zoneCovers(grid, sq.x(), sq.y());
            if (hasTerrain && !coveredByZone) {
                continue;                              // base map terrain — leave it, don't own it
            }
            if (!hasTerrain) {
                grid.getTerrain().add(new TerrainCell(sq.x(), sq.y(), type));
            }
            // Own this cell (shared with another zone if it already covered it) so overlap is tracked.
            owned.add(new TerrainCell(sq.x(), sq.y(), type));
        }
        if (owned.isEmpty()) {
            return false;
        }
        Integer expires = effect.concentration() ? null : enc.getRound() + 10;
        grid.getZones().add(new TerrainZone(type, owned, caster.getId(), effect.name(),
                effect.concentration(), expires));
        return true;
    }

    /**
     * Remove the matching terrain zones, clearing each owned cell unless another zone still
     * covers it. Returns {@code true} when at least one zone was removed (caller should persist).
     */
    public boolean removeTerrainZones(CombatEncounter enc, Predicate<TerrainZone> match) {
        GridState grid = enc.getGridState();
        if (grid == null || grid.getZones() == null || grid.getZones().isEmpty()) {
            return false;
        }
        List<TerrainZone> removed = new ArrayList<>();
        grid.getZones().removeIf(z -> {
            if (match.test(z)) {
                removed.add(z);
                return true;
            }
            return false;
        });
        if (removed.isEmpty()) {
            return false;
        }
        // grid.getZones() now holds only the survivors; only clear a cell no survivor still covers.
        for (TerrainZone z : removed) {
            for (TerrainCell cell : z.cells()) {
                if (!zoneCovers(grid, cell.x(), cell.y())) {
                    grid.getTerrain().removeIf(t -> t.x() == cell.x() && t.y() == cell.y()
                            && t.type() == z.type());
                }
            }
        }
        return true;
    }

    /** True when some active terrain zone covers (x,y). */
    private static boolean zoneCovers(GridState grid, int x, int y) {
        if (grid.getZones() == null) {
            return false;
        }
        for (TerrainZone z : grid.getZones()) {
            for (TerrainCell c : z.cells()) {
                if (c.x() == x && c.y() == y) {
                    return true;
                }
            }
        }
        return false;
    }
}
